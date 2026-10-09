package com.typebit.store

import com.typebit.data.AppSettings
import com.typebit.data.EngineConfigJson
import com.typebit.data.ReceiptExportResult
import com.typebit.data.ReceiptFile
import com.typebit.data.ReceiptRepository
import com.typebit.data.SettingsRepository
import com.typebit.data.TorrentRepository
import com.typebit.engine.EngineEventDto
import com.typebit.engine.ReceiptDto
import com.typebit.engine.ReceiptVerifyResultDto
import com.typebit.engine.TorrentEngine
import com.typebit.engine.TorrentInfoDto
import com.typebit.engine.TorrentSnapshotDto
import com.typebit.model.Torrent
import com.typebit.model.TorrentFilter
import com.typebit.model.TorrentRecord
import com.typebit.model.TorrentStatus
import com.typebit.model.TrackerInfo
import com.typebit.platform.FileIO
import com.typebit.platform.Platform
import com.typebit.util.B64
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Community tracker list fetched at startup (before any torrent starts) so
 * every torrent announces to the most reliable public trackers from the very
 * first moment.
 */
private const val COMMUNITY_TRACKERS_URL = "https://cf.trackerslist.com/best.txt"

/** How often the subscription loop looks whether a refresh is due. */
private const val TRACKER_UPDATE_POLL_MS = 5 * 60 * 1000L

/** Per-URL timeout for a subscription fetch. */
private const val TRACKER_UPDATE_TIMEOUT_MS = 15_000L

/** Subscription URLs honoured per pass (the rest stay stored). */
private const val MAX_SUBSCRIPTION_URLS = 5

/**
 * The single source of truth for the UI.
 *
 * Unidirectional data flow: UI → action → (engine + persistence) → [state]. The engine runs on its
 * own Rust thread; the store owns a poll loop that drains events, refreshes stats and periodically
 * persists resume data.
 *
 * Performance contract: every engine call is a blocking JNI round-trip, so ALL of it runs on a
 * private single-threaded background executor ([engineScope]) — never on the UI thread. Doing it on
 * the main thread was the source of the settings jank and the unresponsive pause/resume buttons (a
 * blocked JNI reply froze the click handler; the state only caught up after navigating away and
 * back). `limitedParallelism(1)` also serializes actions against the poll loop, so the state
 * bookkeeping is race-free.
 */
class AppStore(
        private val engine: TorrentEngine,
        private val settingsRepo: SettingsRepository,
        private val torrentRepo: TorrentRepository,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * Last-resort net for the store's own coroutines.
     *
     * A `CoroutineScope` without a handler reports an uncaught exception to
     * the thread's default handler — on Android that is an immediate process
     * kill (`Thread.UncaughtExceptionHandler` → FATAL EXCEPTION). The poll
     * loop and every download action run in these scopes, so a single bad
     * snapshot/JNI reply would otherwise take the whole app down while the
     * user is browsing the detail tabs. Engine/bridge failures are already
     * mapped to defaults in [com.typebit.engine.NativeTorrentEngine]; this
     * handler covers the remaining store-side computations.
     */
    private val storeFailureHandler = CoroutineExceptionHandler { _, t ->
        println("typebit store coroutine failure — ${t::class.simpleName}: ${t.message}")
        _state.update {
            it.copy(lastError = "内部错误已捕获：${t.message ?: t::class.simpleName}")
        }
    }

    private fun newEngineScope(): CoroutineScope =
            CoroutineScope(
                    SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
                            storeFailureHandler
            )

    private var engineScope: CoroutineScope = newEngineScope()

    // Dedicated single-thread executor for the (non-critical) Peers view. It
    // is deliberately SEPARATE from [engineScope]: a slow `nativePeers`
    // round-trip must never stall the poll loop or any download action.
    // Serializing Peers traffic on its own `limitedParallelism(1)`
    // dispatcher prevents blocking-thread pile-up while guaranteeing the
    // Peers tab can never freeze the rest of the app — even if the engine
    // is momentarily busy, only the Peers view lags, never the download.
    private var peersScope: CoroutineScope = newPeersScope()

    private fun newPeersScope(): CoroutineScope =
            CoroutineScope(
                    SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
                            storeFailureHandler
            )

    // Dedicated executor for one-shot Windows system actions (firewall /
    // ICS). These call out to `netsh` / `powershell` and can take seconds;
    // they must never occupy the serialized engine executor. `IO` is fine
    // here — these are user-initiated, not a polling loop, so blocking
    // JNI threads cannot pile up.
    private var systemScope: CoroutineScope =
            CoroutineScope(
                    SupervisorJob() + Dispatchers.IO.limitedParallelism(2) + storeFailureHandler
            )

    /**
     * Teardown executor. Teardown is JNI (`nativeDestroyEngine` joins the
     * engine worker), file I/O and a bounded `runBlocking` — it must run on
     * a thread of its own when the caller is the UI (Android `onDispose`
     * from an Activity destroy used to do all of this on the main thread,
     * which is an ANR waiting to happen).
     */
    private var shutdownScope: CoroutineScope =
            CoroutineScope(
                    SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + storeFailureHandler
            )

    /**
     * Drives an in-place engine restart (settings that are only read at engine
     * creation). Separate from every other scope on purpose: it stops the
     * engine — which cancels [engineScope] — and then boots a fresh one.
     */
    private fun newRestartScope(): CoroutineScope =
            CoroutineScope(
                    SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + storeFailureHandler
            )

    private var restartScope: CoroutineScope = newRestartScope()

    /**
     * Set as soon as the application starts going down, cleared by [start].
     *
     * A pending restart reads it before and after tearing the engine down, so
     * a window close can never be overtaken by a settings-driven reboot.
     */
    @Volatile private var shutdownBegan = false

    /** The subscription loop; cancelled with [systemScope] on teardown. */
    private var trackerUpdateJob: Job? = null

    /** Serializes subscription passes (see [refreshTrackerSubscription]). */
    private val trackerUpdateLock = Mutex()

    // Persisted app-level records (engine cannot carry category/tags/source).
    // Only touched from [engineScope] — never from the UI thread.
    private var records: List<TorrentRecord> = emptyList()

    /** Proof-of-download receipts persisted in `<appData>/receipts/`. */
    private val receiptRepo = ReceiptRepository()

    /** Full metainfo mirror cache; refetched only when metadata arrives. */
    private val infoCache = HashMap<String, TorrentInfoDto>()

    // Speed bookkeeping: (poll time, downloaded/uploaded bytes) per hash.
    private val lastSeen = HashMap<String, Pair<Long, Long>>()
    private val lastUpSeen = HashMap<String, Pair<Long, Long>>()
    private var lastTotals: Pair<Long, Long>? = null
    private var lastGlobalPoll = 0L

    private var pollJob: Job? = null

    // The boot coroutine itself. Guards `start()` against racing itself:
    // `engineRunning` only flips true at the END of boot, so two rapid
    // `start()` calls (e.g. an Activity recreate during a slow restore)
    // would otherwise boot TWICE — double re-adds and two poll loops.
    private var bootJob: Job? = null

    /** Guards [teardown] against concurrent `stop()` / `stopBlocking()`. */
    private val teardownLock = Any()
    private var stopping = false

    /** Records retargeted from an unwritable save dir during the last boot. */
    private var retargetedRecords = 0

    private var lastSaveAt = 0L

    // Native-applied settings, diffed so a settings edit only crosses the
    // JNI boundary when the value that matters actually changed.
    private var lastAppliedLimits: Pair<Long, Long>? = null
    private var lastAppliedSessionConfig: String? = null
    private var lastAppliedExtraTrackers: Set<String> = emptySet()
    private var settingsSaveJob: Job? = null

    /** Runs [block] on the engine executor (off the UI thread, serialized). */
    private fun onEngine(block: suspend () -> Unit) {
        engineScope.launch { block() }
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    /** Boots the engine, restores state and starts the poll loop. */
    fun start() {
        shutdownBegan = false
        if (_state.value.engineRunning) return
        if (bootJob?.isActive == true) return
        if (!engineScope.isActive) engineScope = newEngineScope()
        if (!peersScope.isActive) peersScope = newPeersScope()
        if (!systemScope.isActive) {
            systemScope =
                    CoroutineScope(
                            SupervisorJob() + Dispatchers.IO.limitedParallelism(2) +
                                    storeFailureHandler
                    )
        }
        if (!shutdownScope.isActive) {
            shutdownScope =
                    CoroutineScope(
                            SupervisorJob() + Dispatchers.IO.limitedParallelism(1) +
                                    storeFailureHandler
                    )
        }
        stopping = false
        bootJob = onEngineJob { boot() }
    }

    private suspend fun boot() {
        val settings = settingsRepo.load()
        val configured = settings.downloads.defaultSavePath
        val saveDir = Platform.resolveSaveDir(configured)
        val saveDirSubstituted = configured.isNotBlank() && saveDir != configured.trim()
        val started = engine.start(EngineConfigJson.engineConfig(settings), saveDir)
        if (!started) {
            val detail =
                (engine as? com.typebit.engine.NativeTorrentEngine)?.lastBridgeFailure
                    ?: "原生库未加载"
            _state.update { it.copy(lastError = "引擎启动失败：$detail") }
            return
        }
        var effectiveSettings = settings
        try {
            records = torrentRepo.loadRecords()
            var retargeted = 0
            records =
                    records.map { rec ->
                        val resolved = Platform.resolveSaveDir(rec.saveDir)
                        if (resolved != rec.saveDir.trim()) {
                            retargeted++
                            rec.copy(saveDir = resolved)
                        } else {
                            rec
                        }
                    }
            if (retargeted > 0) persistRecords()
            retargetedRecords = retargeted
            for (rec in records) {
                reAddRecord(rec)
            }
            for (rec in records) {
                engine.torrentInfo(rec.hash)?.let { infoCache[rec.hash] = it }
            }
            torrentRepo.loadResumeState()?.let { engine.loadState(it) }

            for (rec in records) {
                if (rec.kind == "MAGNET" && rec.pendingSelection) {
                    engine.setHoldData(rec.hash, hold = true)
                }
                if (!rec.paused) engine.start(rec.hash)
            }
            // Apply speed limits.
            applyLimits(effectiveSettings)
        } catch (t: Throwable) {
            _state.update { it.copy(lastError = "恢复部分数据时出错：${t.message ?: t::class.simpleName}") }
        }

        val saveDirNotice =
                when {
                    saveDirSubstituted ->
                            "保存目录不可写，已改用 $saveDir（可在设置中修改）"
                    retargetedRecords > 0 ->
                            "已将 $retargetedRecords 个不可写的保存目录改到 $saveDir"
                    else -> null
                }

        _state.update {
            it.copy(
                    settings = effectiveSettings,
                    engineRunning = true,
                    peerId = engine.peerId(),
                    categories = buildCategories(),
                    tags = buildTags(),
                    lastError = saveDirNotice ?: it.lastError,
            )
        }
        refreshStats()
        if (engineScope.isActive) {
            pollJob = onEngineJob { pollLoop() }
        }
        // Off the engine thread on purpose: the old startup fetch ran inside
        // boot() and held the (single-thread) engine executor for as long as
        // the HTTP request took, delaying every restore action behind it.
        startTrackerUpdates()
    }

    // ------------------------------------------------------------------
    // tracker subscription
    // ------------------------------------------------------------------

    /**
     * Periodic tracker-list refresh, on [systemScope] so a slow or dead URL
     * can never stall the engine.
     *
     * A public trackerslist is the only way a client behind NAT/DHT trouble
     * finds peers, and the list itself rots within weeks: without this a NAS
     * that has been up for months announces to trackers that no longer exist.
     */
    private fun startTrackerUpdates() {
        trackerUpdateJob?.cancel()
        trackerUpdateJob =
                systemScope.launch {
                    while (isActive && !shutdownBegan) {
                        runCatching { refreshTrackerSubscription(force = false) }
                        delay(TRACKER_UPDATE_POLL_MS)
                    }
                }
    }

    /**
     * Runs one subscription pass and returns how many new URLs were merged in.
     *
     * `force` ignores the interval (the "立即更新" button and a settings edit);
     * otherwise a pass only happens when the interval is due. Failures are
     * deliberately non-destructive: if every URL fails, the previous list and
     * its timestamp stay, and the next poll retries.
     *
     * Passes are serialized by [trackerUpdateLock]: the startup pass can be
     * several seconds into a slow fetch when the user sets a subscription URL
     * and asks for an update, and the slower of the two must not win.
     */
    suspend fun refreshTrackerSubscription(force: Boolean): Int =
            trackerUpdateLock.withLock {
                val current = _state.value.settings
                val bt = current.bitTorrent
                if (!force && bt.trackerUpdateHours <= 0) return@withLock 0
                val intervalMs = bt.trackerUpdateHours.coerceAtLeast(1).toLong() * 3_600_000L
                val now = System.currentTimeMillis()
                if (!force && bt.trackerUpdateLastMs > 0 && now - bt.trackerUpdateLastMs < intervalMs) {
                    return@withLock 0
                }
                val urls = subscriptionUrls(bt)
                if (urls.isEmpty()) return@withLock 0

                val fetched = LinkedHashSet<String>()
                var answered = false
                for (url in urls.take(MAX_SUBSCRIPTION_URLS)) {
                    val text =
                            withContext(Dispatchers.IO) {
                                com.typebit.platform.fetchUrlText(url, TRACKER_UPDATE_TIMEOUT_MS)
                            } ?: continue
                    answered = true
                    fetched += com.typebit.data.parseSubscriptionList(text)
                }
                if (!answered) return@withLock 0

                val previous = parseTrackerLines(bt.subscribedTrackers)
                val stored = fetched.joinToString("\n")
                // Re-read the *latest* settings when storing: the fetch takes
                // seconds, and a settings save that landed meanwhile (a switch
                // the user just flipped) must not be reverted by this pass
                // writing a stale snapshot.
                var effective: AppSettings? = null
                _state.update { state ->
                    if (subscriptionUrls(state.settings.bitTorrent) != urls) {
                        // The subscription changed while we were fetching: this
                        // answer belongs to a list nobody asked for any more.
                        state
                    } else {
                        val merged =
                                state.settings.copy(
                                        bitTorrent =
                                                state.settings.bitTorrent.copy(
                                                        subscribedTrackers = stored,
                                                        trackerUpdateLastMs = now,
                                                ),
                                )
                        effective = merged
                        state.copy(settings = merged)
                    }
                }
                val applied = effective ?: return@withLock 0
                // applySettings diffs the announce list against what is already
                // applied and pushes only the new URLs to every torrent (plus
                // new ones), then persists — the same path a manual edit in 设置
                // takes.
                onEngine { applySettings(applied) }
                (fetched - previous).size
            }

    /** Subscription URLs; the built-in community list is the default. */
    private fun subscriptionUrls(bt: com.typebit.data.BitTorrentSettings): List<String> {
        val configured = parseTrackerLines(bt.trackerUpdateUrl).toList()
        return configured.ifEmpty { listOf(COMMUNITY_TRACKERS_URL) }
    }

    /**
     * Starts a subscription refresh without waiting for it.
     *
     * The WebUI has to answer immediately (the user is looking at a spinner)
     * while a dead URL can take 15 seconds per host to time out.
     */
    fun requestTrackerSubscriptionRefresh() {
        if (!systemScope.isActive) return
        systemScope.launch { runCatching { refreshTrackerSubscription(force = true) } }
    }

    private fun parseTrackerLines(raw: String): Set<String> =
            raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Parsed announce list (manual + subscribed) as a set of trimmed URLs. */
    private fun trackersSet(settings: AppSettings): Set<String> =
            settings.bitTorrent
                    .allTrackers
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()

    private fun onEngineJob(block: suspend () -> Unit): Job = engineScope.launch { block() }

    /**
     * Stops the engine and flushes persistence **without blocking the caller**.
     *
     * This is the path a UI lifecycle takes (Android `DisposableEffect`
     * onDispose when the Activity is destroyed). Persistence plus
     * `nativeDestroyEngine`/engine-thread join are JNI + disk work: running
     * them on the main thread froze the UI for seconds and could be killed as
     * an ANR, so they always run on [shutdownScope].
     */
    fun stop() {
        shutdownBegan = true
        teardown(blocking = false)
    }

    /**
     * Blocking variant for process-exit paths (desktop window close), where
     * the process may be gone a moment later and the engine must be joined
     * deterministically. Idempotent with [stop].
     */
    fun stopBlocking() {
        shutdownBegan = true
        teardown(blocking = true)
    }

    private fun teardown(blocking: Boolean) {
        synchronized(teardownLock) {
            if (stopping) return
            stopping = true
        }
        bootJob?.cancel()
        pollJob?.cancel()
        trackerUpdateJob?.cancel()
        engineScope.cancel()
        peersScope.cancel()
        systemScope.cancel()

        // Persistence is best-effort and is NEVER allowed to skip the engine
        // teardown: `engine.stop()` (native destroy) is outside the timeout.
        // If it were skipped, the engine worker thread would leak and the next
        // store start would spawn a SECOND engine — two engines racing on the
        // same port and the same `.part` files silently corrupts downloads.
        val work: suspend () -> Unit = {
            withTimeoutOrNull(5_000) {
                settingsSaveJob?.cancel()
                settingsRepo.save(_state.value.settings)
                persistRecords()
                persistResume()
            }
            engine.stop()
            com.typebit.platform.Platform.ensureBackgroundMode(false)
            _state.update { it.copy(engineRunning = false) }
            synchronized(teardownLock) { stopping = false }
        }

        if (blocking) {
            runBlocking { work() }
        } else {
            shutdownScope.launch { work() }
        }
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    /**
     * Parses `.torrent` bytes without adding — add-dialog preview. Blocking JNI parse; callers
     * should run it off the UI thread.
     */
    fun parseTorrentFile(bytes: ByteArray): com.typebit.engine.TorrentInfoDto? =
            engine.parseTorrent(bytes)

    /**
     * Creates a v1 `.torrent` from local files (blocking — call off the UI thread). `files` is
     * `(absolutePath, fileName)` pairs; `pieceLength` must be a supported power of two (16 KiB ..
     * 256 MiB).
     */
    fun makeTorrent(options: com.typebit.engine.MakeTorrentOptions): ByteArray? =
            engine.makeTorrent(options)

    /** Live progress of the in-flight [makeTorrent] (safe to poll from the UI). */
    fun makeTorrentProgress(): com.typebit.engine.MakeTorrentProgress =
            engine.makeTorrentProgress()

    /** Cancels the in-flight [makeTorrent]; true when a build was signalled. */
    fun cancelMakeTorrent(): Boolean = engine.cancelMakeTorrent()

    fun addTorrentFile(bytes: ByteArray, fileName: String) {
        val s = _state.value.settings
        addTorrentFileEx(
                bytes = bytes,
                fileName = fileName,
                saveDir = Platform.resolveSaveDir(s.downloads.defaultSavePath),
                category = "",
                tags = emptyList(),
                paused = s.downloads.addTorrentsInPause,
                filePriorities = emptyList(),
        )
    }

    /** Adds a `.torrent` with the add-dialog options applied. */
    fun addTorrentFileEx(
            bytes: ByteArray,
            fileName: String,
            saveDir: String,
            category: String,
            tags: List<String>,
            paused: Boolean,
            filePriorities: List<Int> = emptyList(),
    ) = onEngine {
        val hash =
                engine.addTorrent(bytes, saveDir, filePriorities)
                        ?: run {
                            _state.update { it.copy(lastError = "无法解析种子文件：$fileName") }
                            return@onEngine
                        }
        val info = engine.torrentInfo(hash)
        if (info != null) infoCache[hash] = info
        val record =
                TorrentRecord(
                        hash = hash,
                        name = info?.name ?: fileName.removeSuffix(".torrent"),
                        kind = "FILE",
                        saveDir = saveDir,
                        data = B64.encode(bytes),
                        addedAt = System.currentTimeMillis(),
                        paused = paused,
                        category = category,
                        tags = tags,
                        filePriorities = filePriorities,
                )
        records = records + record
        persistRecords()
        if (!record.paused) engine.start(hash)
        refreshStats()
    }

    fun addMagnet(uri: String) {
        val s = _state.value.settings
        addMagnetEx(
                uri = uri,
                saveDir = Platform.resolveSaveDir(s.downloads.defaultSavePath),
                category = "",
                tags = emptyList(),
                paused = s.downloads.addTorrentsInPause,
                filePriorities = emptyList(),
        )
    }

    /** Adds a magnet with the add-dialog options applied. */
    fun addMagnetEx(
            uri: String,
            saveDir: String,
            category: String,
            tags: List<String>,
            paused: Boolean,
            filePriorities: List<Int> = emptyList(),
    ) = onEngine {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return@onEngine
        val hash =
                engine.addMagnet(trimmed, saveDir)
                        ?: run {
                            _state.update { it.copy(lastError = "无法解析磁力链接") }
                            return@onEngine
                        }
        val info = engine.torrentInfo(hash)
        if (info != null) infoCache[hash] = info
        val record =
                TorrentRecord(
                        hash = hash,
                        name = info?.name ?: "magnet",
                        kind = "MAGNET",
                        saveDir = saveDir,
                        data = trimmed,
                        addedAt = System.currentTimeMillis(),
                        paused = paused,
                        category = category,
                        tags = tags,
                        filePriorities = filePriorities,
                )
        records = records + record
        persistRecords()
        if (!record.paused) engine.start(hash)
        refreshStats()
    }

    /** Starts or resumes a torrent. */
    fun start(hash: String) = resume(hash)

    /**
     * Pauses a torrent. The status flips to PAUSED immediately (optimistic UI), then the engine +
     * records are updated on the background executor; the poll tick confirms the authoritative
     * state. Never blocks the UI.
     */
    fun pause(hash: String) {
        _state.update { s ->
            s.copy(
                    torrents =
                            s.torrents.map { t ->
                                if (t.hash == hash && t.status != TorrentStatus.PAUSED) {
                                    t.copy(status = TorrentStatus.PAUSED)
                                } else t
                            },
            )
        }
        onEngine {
            engine.pause(hash)
            setRecordPaused(hash, paused = true)
            refreshStats()
        }
    }

    /** Resumes a paused torrent. Optimistic status, then authoritative. */
    fun resume(hash: String) {
        _state.update { s ->
            s.copy(
                    torrents =
                            s.torrents.map { t ->
                                if (t.hash == hash && t.status == TorrentStatus.PAUSED) {
                                    t.copy(
                                            status =
                                                    if (t.isComplete) TorrentStatus.SEEDING
                                                    else TorrentStatus.DOWNLOADING
                                    )
                                } else t
                            },
            )
        }
        onEngine {
            engine.start(hash)
            setRecordPaused(hash, paused = false)
            refreshStats()
        }
    }

    /** Removes a torrent. Optimistic UI, then authoritative cleanup. */
    fun remove(hash: String) {
        _state.update {
            it.copy(
                    torrents = it.torrents.filterNot { t -> t.hash == hash },
                    selectedHash = if (it.selectedHash == hash) null else it.selectedHash,
            )
        }
        onEngine {
            engine.remove(hash)
            infoCache.remove(hash)
            lastSeen.remove(hash)
            lastUpSeen.remove(hash)
            records = records.filterNot { it.hash == hash }
            persistRecords()
            _state.update {
                it.copy(
                        torrents = it.torrents.filterNot { t -> t.hash == hash },
                        selectedHash = if (it.selectedHash == hash) null else it.selectedHash,
                )
            }
        }
    }

    fun select(hash: String?) {
        _state.update { it.copy(selectedHash = hash) }
    }

    /**
     * Renames the torrent itself (display name). The engine keeps writing
     * files under their original paths; the new name is what the UI shows
     * and what share dialogs carry (`dn=`). Persisted across restarts.
     */
    fun renameTorrent(hash: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        onEngine {
            if (engine.renameTorrent(hash, trimmed)) {
                engine.torrentInfo(hash)?.let { infoCache[hash] = it }
                records = records.map { if (it.hash == hash) it.copy(name = trimmed) else it }
                persistRecords()
                refreshStats()
            } else {
                _state.update { it.copy(lastError = "重命名失败：名称不能包含路径分隔符") }
            }
        }
    }

    /**
     * Magnet magnet-link helper: `magnet:?xt=urn:btih:<hash>&dn=<name>`.
     */
    fun magnetLink(hash: String, name: String): String {
        val dn = name.ifBlank { hash }
        return "magnet:?xt=urn:btih:$hash&dn=$dn"
    }

    // -- proof-of-download receipts ------------------------------------------

    /**
     * Exports a signed proof-of-download receipt for `hash` over
     * `[0, downloadedBytes]`, attested to the wall-clock window
     * `[addedAtMs, now]` (unix seconds). On success the receipt JSON is
     * persisted to `<appData>/receipts/<hash>.receipt.json` and the path is
     * returned. Runs on the engine executor (blocking JNI round-trip).
     */
    suspend fun exportReceipt(
        hash: String,
        downloadedBytes: Long,
        addedAtMs: Long,
    ): ReceiptExportResult = withContext(engineScope.coroutineContext) {
        val nowSec = System.currentTimeMillis() / 1000
        val addedSec = (addedAtMs / 1000).coerceIn(nowSec - 365L * 24 * 3600, nowSec)
        val start = 0L
        val end = downloadedBytes.coerceAtLeast(0L)
        val json = engine.exportReceipt(hash, start, end, addedSec, nowSec)
            ?: return@withContext ReceiptExportResult(error = "引擎未运行")
        val err = runCatching {
            Json.parseToJsonElement(json).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (err != null) return@withContext ReceiptExportResult(error = err)
        val receipt = runCatching {
            Json.decodeFromString<ReceiptDto>(json)
        }.getOrNull()
        if (receipt == null) {
            return@withContext ReceiptExportResult(error = "回执响应无法解析")
        }
        val path = receiptRepo.save(hash, json)
        ReceiptExportResult(receipt = receipt, path = path)
    }

    /**
     * Verifies a receipt JSON (Ed25519 signature + structural integrity)
     * through the engine. Runs on the engine executor. Returns a truthful
     * result — `ok=false` for forged, tampered or malformed receipts.
     */
    suspend fun verifyReceiptJson(json: String): ReceiptVerifyResultDto =
        withContext(engineScope.coroutineContext) {
            engine.verifyReceipt(json)
        }

    /** All saved receipts, newest first. */
    fun listReceipts(): List<ReceiptFile> = receiptRepo.list()

    /** Saved receipts whose content root (infohash) matches `hash`. */
    fun listReceiptsFor(hash: String): List<ReceiptFile> = receiptRepo.listFor(hash)

    /** Deletes a saved receipt file. Returns false when it did not exist. */
    fun deleteReceipt(path: String): Boolean = receiptRepo.delete(path)

    /** Raw JSON of a saved receipt, or null when the file is gone. */
    fun readReceipt(path: String): String? = receiptRepo.read(path)

    /**
     * Two-phase magnet add, phase 1: adds the magnet (already STARTED so the
     * engine fetches metadata) and returns its hash, or null on parse
     * failure. The dialog then calls [waitMetadata] to show the file tree
     * and [commitMagnetSelection] / [cancelMagnetPending] to finish. Runs on
     * the engine executor; the record is persisted before returning.
     */
    suspend fun addMagnetResolve(uri: String, saveDir: String): String? =
            withContext(engineScope.coroutineContext) {
                val trimmed = uri.trim()
                if (trimmed.isEmpty()) return@withContext null
                val hash =
                        engine.addMagnet(trimmed, saveDir)
                                ?: run {
                                    _state.update { it.copy(lastError = "无法解析磁力链接") }
                                    return@withContext null
                                }
                val record =
                        TorrentRecord(
                                hash = hash,
                                name = "magnet",
                                kind = "MAGNET",
                                saveDir = saveDir,
                                data = trimmed,
                                addedAt = System.currentTimeMillis(),
                                paused = false,
                                pendingSelection = true,
                        )
                records = records + record
                persistRecords()
                engine.start(hash)
                engine.setHoldData(hash, hold = true)
                refreshStats()
                hash
            }

    /**
     * Two-phase magnet add, phase 2a: waits (off the engine executor) until
     * the magnet's metadata mirror is ready or [timeoutMs] elapses. Returns
     * the full metainfo (file table) so the dialog can render the selection
     * tree, or null on timeout.
     */
    suspend fun waitMetadata(hash: String, timeoutMs: Long): com.typebit.engine.TorrentInfoDto? =
            withContext(peersScope.coroutineContext) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val info = engine.torrentInfo(hash)
                    if (info?.metadata_ready == true) {
                        infoCache[hash] = info
                        return@withContext info
                    }
                    delay(500)
                }
                engine.torrentInfo(hash)
            }

    /**
     * Two-phase magnet add, phase 2b: applies the user's per-file priorities
     * (0=Skip, 1=Normal, 2=High) to the now-resolved magnet and persists the
     * raw `info` dict so a restart never re-fetches metadata. The torrent is
     * already running (phase 1 started it to fetch metadata), so this only
     * reshapes which files are requested — unless `paused` was chosen, in
     * which case it is paused after the priorities land.
     */
    fun commitMagnetSelection(hash: String, filePriorities: List<Int>, paused: Boolean = false) =
            onEngine {
                engine.setFilePriorities(hash, filePriorities)
                records =
                        records.map { rec ->
                            if (rec.hash == hash) {
                                rec.copy(
                                        filePriorities = filePriorities,
                                        pendingSelection = false,
                                )
                            } else rec
                        }
                persistRecords()
                engine.torrentInfoRaw(hash)?.let { raw ->
                    if (raw.isNotBlank()) {
                        records =
                                records.map {
                                    if (it.hash == hash && it.infoBase64 != raw) {
                                        it.copy(infoBase64 = raw)
                                    } else it
                                }
                        persistRecords()
                    }
                }
                if (paused) {
                    engine.pause(hash)
                    setRecordPaused(hash, paused = true)
                }
                refreshStats()
            }

    /** Removes a magnet the user cancelled in the add dialog (phase 2 cancel). */
    fun cancelMagnetPending(hash: String) = onEngine {
        engine.remove(hash)
        infoCache.remove(hash)
        lastSeen.remove(hash)
        lastUpSeen.remove(hash)
        records = records.filterNot { it.hash == hash }
        persistRecords()
        _state.update {
            it.copy(
                    torrents = it.torrents.filterNot { t -> t.hash == hash },
                    selectedHash = if (it.selectedHash == hash) null else it.selectedHash,
            )
        }
    }

    /**
     * Keeps a magnet whose metadata did not arrive inside the add dialog's
     * wait window. The magnet is already a real task (it was added and
     * started in phase 1); releasing the data hold lets it download with the
     * default all-files selection as soon as the engine finishes fetching
     * metadata in the background — exactly how qBittorrent treats a magnet
     * that resolves slowly. Without this, a slow DHT/tracker bootstrap made
     * the dialog remove the magnet on timeout, so the user had to re-add it
     * repeatedly and it never appeared to load.
     */
    fun releaseMagnetPending(hash: String) = onEngine {
        engine.setHoldData(hash, hold = false)
        records = records.map { if (it.hash == hash) it.copy(pendingSelection = false) else it }
        persistRecords()
        refreshStats()
    }

    /**
     * Sets one file's download priority at runtime (0=Skip, 1=Normal, 2=High) and persists it.
     * Skipped files stop being requested.
     */
    fun setFilePriority(hash: String, file: Int, priority: Int) = onEngine {
        if (engine.setFilePriority(hash, file, priority)) {
            records =
                    records.map { rec ->
                        if (rec.hash == hash) {
                            val prio = rec.filePriorities.toMutableList()
                            while (prio.size <= file) prio.add(1)
                            prio[file] = priority
                            rec.copy(filePriorities = prio)
                        } else rec
                    }
            persistRecords()
        }
    }

    /**
     * Bulk priority change (a directory toggle in the file tree).
     *
     * Uses the engine's ATOMIC `setFilePriorities` with the full index-aligned
     * array instead of N `setFilePriority` calls: one JNI round-trip, one
     * scheduler re-plan, and — for a two-phase magnet — a single hold release.
     */
    fun setFilePriorities(hash: String, updates: Map<Int, Int>) = onEngine {
        if (updates.isEmpty()) return@onEngine
        val rec = records.firstOrNull { it.hash == hash }
        val size = maxOf(updates.keys.max() + 1, rec?.filePriorities?.size ?: 0)
        val prio = MutableList(size) { rec?.filePriorities?.getOrNull(it) ?: 1 }
        updates.forEach { (index, p) -> if (index in 0 until size) prio[index] = p.coerceIn(0, 2) }
        if (engine.setFilePriorities(hash, prio)) {
            records =
                    records.map { r ->
                        if (r.hash == hash) r.copy(filePriorities = prio) else r
                    }
            persistRecords()
        }
    }

    /** Adds a tracker URL to a running torrent and persists it. */
    fun addTracker(hash: String, url: String) = onEngine {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return@onEngine
        if (engine.addTracker(hash, trimmed)) {
            records =
                    records.map { rec ->
                        if (rec.hash == hash) {
                            rec.copy(
                                    trackers =
                                            if (trimmed in rec.trackers) rec.trackers
                                            else rec.trackers + trimmed,
                                    removedTrackers = rec.removedTrackers - trimmed,
                            )
                        } else rec
                    }
            persistRecords()
        }
    }

    /** Removes a tracker URL from a running torrent and persists it. */
    fun removeTracker(hash: String, url: String) = onEngine {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return@onEngine
        if (engine.removeTracker(hash, trimmed)) {
            records =
                    records.map { rec ->
                        if (rec.hash == hash) {
                            rec.copy(
                                    trackers = rec.trackers - trimmed,
                                    removedTrackers =
                                            if (trimmed in rec.removedTrackers) rec.removedTrackers
                                            else rec.removedTrackers + trimmed,
                            )
                        } else rec
                    }
            persistRecords()
        }
    }

    fun setFilter(filter: TorrentFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun setSearch(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    /**
     * Applies a settings edit. The UI state updates immediately; disk I/O and engine calls happen
     * on the background executor, diffed so they only cross the JNI boundary when the relevant
     * value changed, and the JSON write is coalesced (rapid edits collapse into one save).
     */
    fun updateSettings(settings: AppSettings) {
        _state.update { it.copy(settings = settings) }
        onEngine { applySettings(settings) }
    }

    /**
     * True when the edit can only take effect by rebuilding the engine.
     *
     * These fields are read once, at engine creation ([EngineConfigJson.engineConfig]):
     * the listen port, DHT/LSD switches, UPnP/NAT-PMP, the resolver and IPv6
     * policy, the SOCKS5 proxy and the disk cache. Everything else — limits,
     * concurrency, per-session defaults, extra trackers — is applied live.
     */
    fun settingsNeedEngineRestart(before: AppSettings, after: AppSettings): Boolean {
        val bc = before.connection
        val ac = after.connection
        val bb = before.bitTorrent
        val ab = after.bitTorrent
        return bc.listenPort != ac.listenPort ||
            bc.useRandomPort != ac.useRandomPort ||
            bc.maxConnections != ac.maxConnections ||
            bc.enableDoh != ac.enableDoh ||
            bc.dohProviders != ac.dohProviders ||
            bc.enableIpv6 != ac.enableIpv6 ||
            bc.allowLanWebseeds != ac.allowLanWebseeds ||
            bc.proxyType != ac.proxyType ||
            bc.proxyHost != ac.proxyHost ||
            bc.proxyPort != ac.proxyPort ||
            bc.proxyAuthEnabled != ac.proxyAuthEnabled ||
            bc.proxyUsername != ac.proxyUsername ||
            bc.proxyPassword != ac.proxyPassword ||
            bb.enableDht != ab.enableDht ||
            bb.enableLsd != ab.enableLsd ||
            bb.enableUpnp != ab.enableUpnp ||
            bb.enableNatPmp != ab.enableNatPmp ||
            bb.cacheBytes != ab.cacheBytes
    }

    /**
     * Rebuilds the engine in place so creation-time settings apply.
     *
     * The teardown flushes resume data, settings and records before the engine
     * goes down, and [start] re-adds every record, so a restart is the same
     * event as an app restart — without dropping the WebUI or the process.
     * Runs on its own one-thread scope: the engine executor must stay free for
     * the teardown that is about to cancel it.
     */
    fun restartEngine() {
        if (!restartScope.isActive) restartScope = newRestartScope()
        restartScope.launch {
            if (shutdownBegan) return@launch
            teardown(blocking = true)
            // An application exit that started while we were tearing down wins:
            // booting an engine after `engine.stop()` would leak the native
            // worker and race the next start on the same port and `.part` files.
            if (shutdownBegan) return@launch
            start()
        }
    }

    private suspend fun applySettings(settings: AppSettings) {
        val limits = effectiveLimits(settings.speed)
        if (limits != lastAppliedLimits) {
            engine.setGlobalLimits(limits.first, limits.second)
            lastAppliedLimits = limits
        }
        val cfg = EngineConfigJson.sessionConfig(settings)
        if (cfg != lastAppliedSessionConfig) {
            engine.setSessionConfig(cfg)
            lastAppliedSessionConfig = cfg
        }

        val trackersNow =
                settings.bitTorrent
                        .allTrackers
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
        if (trackersNow != lastAppliedExtraTrackers) {
            val added = trackersNow - lastAppliedExtraTrackers
            if (added.isNotEmpty()) {
                for (rec in records) {
                    for (url in added) engine.addTracker(rec.hash, url)
                }
                // Refresh the mirrors so the Tracker tab shows the new URLs.
                for (rec in records) {
                    engine.torrentInfo(rec.hash)?.let { infoCache[rec.hash] = it }
                }
                refreshStats()
            }
            lastAppliedExtraTrackers = trackersNow
        }
        settingsSaveJob?.cancel()
        settingsSaveJob = onEngineJob {
            settingsRepo.save(settings)
        }
    }

    fun setCategory(hash: String, category: String) = onEngine {
        records = records.map { if (it.hash == hash) it.copy(category = category) else it }
        persistRecords()
        _state.update {
            it.copy(
                    categories = buildCategories(),
                    torrents =
                            it.torrents.map { t ->
                                if (t.hash == hash) t.copy(category = category) else t
                            },
            )
        }
    }

    fun toggleTag(hash: String, tag: String) = onEngine {
        records =
                records.map { r ->
                    if (r.hash == hash) {
                        val tags = if (tag in r.tags) r.tags - tag else r.tags + tag
                        r.copy(tags = tags)
                    } else r
                }
        persistRecords()
        _state.update {
            it.copy(
                    tags = buildTags(),
                    torrents =
                            it.torrents.map { t ->
                                if (t.hash == hash)
                                        t.copy(tags = records.first { r -> r.hash == hash }.tags)
                                else t
                            },
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    // ------------------------------------------------------------------
    // Windows system integration (firewall / ICS) — desktop only
    // ------------------------------------------------------------------

    /** The actual bound listen port, falling back to the configured one. */
    private fun listenPort(): Int =
        _state.value.listenPort.takeIf { it > 0 }
            ?: if (_state.value.settings.connection.useRandomPort) 0
            else _state.value.settings.connection.listenPort

    /** Runs a firewall/ICS action off the engine thread and surfaces the
     *  result in `state.systemOk` / `state.systemMessage`. */
    private fun runSystemAction(block: suspend () -> com.typebit.engine.SystemResultDto) {
        systemScope.launch {
            val r = block()
            _state.update { it.copy(systemOk = r.ok, systemMessage = r.message) }
        }
    }

    /** Adds (or refreshes) the Windows firewall rules for the listen port. */
    fun configureFirewall() {
        val port = listenPort()
        if (port <= 0) {
            _state.update {
                it.copy(systemOk = false, systemMessage = "监听端口未知（引擎尚未启动或随机端口模式）")
            }
            return
        }
        runSystemAction { engine.firewallAdd(port) }
    }

    /** Elevated retry (one UAC prompt) for [configureFirewall]. */
    fun configureFirewallElevated() {
        val port = listenPort()
        if (port <= 0) {
            _state.update {
                it.copy(systemOk = false, systemMessage = "监听端口未知（引擎尚未启动或随机端口模式）")
            }
            return
        }
        runSystemAction { engine.firewallAddElevated(port) }
    }

    /** Removes the inbound firewall rules for the listen port. */
    fun removeFirewallRules() {
        val port = listenPort()
        if (port <= 0) return
        runSystemAction { engine.firewallRemove(port) }
    }

    /** Refreshes the firewall status line in the settings card. */
    fun refreshFirewallStatus() {
        val port = listenPort()
        if (port <= 0) return
        runSystemAction { engine.firewallStatus(port) }
    }

    /** Enables Internet Connection Sharing (explicit, admin-gated). */
    fun configureIcs() = runSystemAction { engine.icsEnable() }

    /** Disables Internet Connection Sharing on all shared connections. */
    fun disableIcs() = runSystemAction { engine.icsDisable() }

    /** Refreshes the ICS status line. */
    fun refreshIcsStatus() = runSystemAction { engine.icsStatus() }

    // ------------------------------------------------------------------
    // poll loop
    // ------------------------------------------------------------------

    private suspend fun pollLoop() {
        while (engineScope.isActive && pollJob?.isActive == true) {
            val interval = _state.value.settings.behavior.refreshIntervalMs.coerceIn(200, 5000)
            delay(interval.toLong())

            val saveNow = drainEvents(engine.takeEvents())

            refreshStats()

            val now = System.currentTimeMillis()
            if (saveNow || now - lastSaveAt > 30_000) {
                lastSaveAt = now
                persistResume()
            }
        }
    }

    /**
     * Applies engine events without rebuilding the whole list per event: deltas are aggregated per
     * torrent first, then applied in one pass. Returns true when a torrent completed or metadata
     * arrived — states that must be persisted immediately.
     */
    private fun drainEvents(events: List<EngineEventDto>): Boolean {
        if (events.isEmpty()) return false
        val complete = HashSet<String>()
        val metadata = HashSet<String>()
        _state.update { s ->
            var dht = s.dhtNodes
            var leechCount = s.antiLeechCount
            var leechClients = s.antiLeechClients
            var pmPhase = s.portMapPhase
            var pmPort = s.portMapPort
            var engineNotice: String? = null
            val antiLeechOn = s.settings.bitTorrent.antiLeechEnabled

            val peerAbs = HashMap<String, Int>()
            val peerAdj = HashMap<String, Int>()
            val pieceAdj = HashMap<String, Int>()

            for (ev in events) {
                when (ev.t) {
                    1 -> peerAdj.merge(ev.h, 1, Int::plus)
                    2 -> pieceAdj.merge(ev.h, 1, Int::plus)
                    3 -> Unit // hash failure — no state change surfaced
                    4 -> if (ev.h.isNotEmpty()) complete.add(ev.h)
                    5 -> if (ev.h.isNotEmpty()) metadata.add(ev.h)
                    6 -> Unit // metadata failed — surfaced via status
                    7 -> if (ev.h.isNotEmpty()) peerAbs[ev.h] = ev.peers ?: 0
                    8 -> dht = ev.n ?: dht
                    9 ->
                            if (antiLeechOn) {
                                leechCount++
                                val name = ev.c ?: "未知客户端"
                                if (name !in leechClients) {
                                    leechClients = (leechClients + name).takeLast(20)
                                }
                            }
                    10 ->
                            if (antiLeechOn) {
                                leechCount++
                                val reason =
                                        when (ev.r) {
                                            "corrupt" -> "封禁:供块校验失败"
                                            "protocol" -> "封禁:协议违规"
                                            "free-ride" -> "封禁:只下不上"
                                            else -> "封禁:${ev.r ?: "未知原因"}"
                                        }
                                val label = "${reason} ${ev.a ?: ""}".trim()
                                if (label !in leechClients) {
                                    leechClients = (leechClients + label).takeLast(20)
                                }
                            }
                    11 -> {
                        val msg =
                                when (ev.code) {
                                    0 -> "引擎：UDP 端口无法打开，DHT 与 UDP tracker 已停用（HTTP tracker 仍可用）"
                                    1 -> "引擎：DHT 引导失败，无法解析引导路由器（DHT 休眠，tracker 不受影响）"
                                    2 -> {
                                        val d = ev.detail?.takeIf { it.isNotBlank() }
                                        "引擎：内部错误已自动恢复（下载不受影响）" + (if (d != null) "：$d" else "")
                                    }
                                    else -> "引擎：${ev.detail ?: "未知错误"}"
                                }
                        engineNotice = msg
                    }
                    12 -> {
                        // UPnP/NAT-PMP port-mapping lifecycle (0.1.7).
                        if (ev.phase != null) pmPhase = ev.phase
                        if (ev.port != null && ev.port!! > 0) pmPort = ev.port!!
                    }
                }
            }

            if (peerAbs.isEmpty() &&
                            peerAdj.isEmpty() &&
                            pieceAdj.isEmpty() &&
                            complete.isEmpty() &&
                            metadata.isEmpty()
            ) {
                return@update s.copy(
                        dhtNodes = dht,
                        antiLeechCount = leechCount,
                        antiLeechClients = leechClients,
                        portMapPhase = pmPhase,
                        portMapPort = pmPort,
                        lastError = engineNotice ?: s.lastError,
                )
            }

            val torrents =
                    s.torrents.map { t ->
                        var out = t
                        peerAbs[t.hash]?.let { out = out.copy(peers = it) }
                        peerAdj[t.hash]?.let {
                            out = out.copy(peers = (out.peers + it).coerceAtLeast(0))
                        }
                        pieceAdj[t.hash]?.let {
                            out =
                                    out.copy(
                                            havePieces =
                                                    (out.havePieces + it).coerceAtMost(
                                                            out.pieceCount.coerceAtLeast(0)
                                                    )
                                    )
                        }
                        if (t.hash in complete) {
                            out =
                                    out.copy(
                                            status = TorrentStatus.SEEDING,
                                            progress = 1.0,
                                            completedAt = System.currentTimeMillis()
                                    )
                        }
                        if (t.hash in metadata) out = out.copy(metadataReady = true)
                        out
                    }

            s.copy(
                    dhtNodes = dht,
                    torrents = torrents,
                    antiLeechCount = leechCount,
                    antiLeechClients = leechClients,
                    portMapPhase = pmPhase,
                    portMapPort = pmPort,
                    lastError = engineNotice ?: s.lastError,
            )
        }
        return complete.isNotEmpty() || metadata.isNotEmpty()
    }

    /**
     * Per-tick refresh driven by ONE batched native snapshot (DHT count, global totals and every
     * torrent's runtime stats). Full metainfo is only refetched when the snapshot reports
     * freshly-arrived metadata, so the per-tick JNI traffic is constant regardless of torrent
     * count.
     */
    private fun refreshStats() {
        val now = System.currentTimeMillis()
        val snap = engine.snapshot()
        val byHash = snap.torrents.associateBy { it.h }
        val totals = snap.totalsPair
        val dt = (now - lastGlobalPoll).coerceAtLeast(1L)
        val downRate =
                if (lastTotals == null) 0L else (totals.first - lastTotals!!.first) * 1000 / dt
        val upRate =
                if (lastTotals == null) 0L else (totals.second - lastTotals!!.second) * 1000 / dt
        lastTotals = totals
        lastGlobalPoll = now
        for (row in snap.torrents) {
            if (row.meta && infoCache[row.h]?.metadata_ready != true) {
                engine.torrentInfo(row.h)?.let { infoCache[row.h] = it }
                val rec = records.firstOrNull { it.hash == row.h }
                if (rec != null && rec.kind == "MAGNET") {
                    if (rec.filePriorities.isNotEmpty()) applyPriorities(row.h, rec.filePriorities)
                    if (rec.renames.isNotEmpty()) applyRenames(row.h, rec.renames)
                    engine.torrentInfo(row.h)?.let { infoCache[row.h] = it }
                    val raw = engine.torrentInfoRaw(row.h)
                    if (raw != null && raw != rec.infoBase64) {
                        records =
                                records.map {
                                    if (it.hash == row.h) it.copy(infoBase64 = raw) else it
                                }
                        persistRecords()
                    }
                }
            }
        }

        _state.update { s ->
            val updated =
                    records.map { rec ->
                        val base = s.torrents.firstOrNull { it.hash == rec.hash }
                        buildTorrent(rec, base, byHash[rec.hash], now)
                    }
            s.copy(
                    torrents = updated,
                    globalDownRate = downRate.coerceAtLeast(0),
                    globalUpRate = upRate.coerceAtLeast(0),
                    totalDownloaded = totals.first,
                    totalUploaded = totals.second,
                    dhtNodes = snap.dht,
                    trackerCount = snap.trackers,
                    extIp = snap.extIp,
                    extPort = snap.extPort,
                    portMapPhase = snap.pmPhase,
                    portMapPort = snap.pmPort,
                    listenPort = snap.listenPort,
                    lsdSent = snap.lsd_sent,
                    lsdRecv = snap.lsd_recv,
                    lsdPeers = snap.lsd_peers,
            )
        }
        val anyActive =
                _state.value.torrents.any {
                    it.status == TorrentStatus.DOWNLOADING ||
                            it.status == TorrentStatus.SEEDING ||
                            it.status == TorrentStatus.FETCHING_METADATA
                }
        val backgroundAllowed = _state.value.settings.behavior.backgroundDownloads
        com.typebit.platform.Platform.ensureBackgroundMode(anyActive && backgroundAllowed)
    }

    /**
     * Rebuilds one display model from the snapshot row + the cached full metainfo. The status is
     * deterministic — paused wins, then complete (seeding), then metadata availability — instead of
     * the old heuristic that guessed from stale progress deltas and made pause/resume appear
     * broken.
     */
    private fun buildTorrent(
            rec: TorrentRecord,
            base: Torrent?,
            row: TorrentSnapshotDto?,
            now: Long
    ): Torrent {
        val info = infoCache[rec.hash]
        val paused = (row?.paused ?: false) || rec.paused
        val complete = row?.c ?: (base?.isComplete == true)
        val progress = row?.p ?: base?.progress ?: 0.0
        val downloaded = row?.d ?: base?.downloadedBytes ?: 0L
        val metadataReady = row?.meta ?: (info?.metadata_ready ?: base?.metadataReady ?: false)
        val havePieces = row?.have?.toInt() ?: base?.havePieces ?: 0

        val status =
                when {
                    paused -> TorrentStatus.PAUSED
                    complete -> TorrentStatus.SEEDING
                    !metadataReady -> TorrentStatus.FETCHING_METADATA
                    else -> TorrentStatus.DOWNLOADING
                }

        val uploaded = row?.u ?: base?.uploadedBytes ?: 0L
        // Per-torrent download/upload rates from byte deltas.
        val prev = lastSeen[rec.hash]
        val dt = (now - (prev?.first ?: now)).coerceAtLeast(1L)
        val downRate =
                if (prev == null) 0L else (downloaded - prev.second).coerceAtLeast(0) * 1000 / dt
        lastSeen[rec.hash] = now to downloaded
        val prevUp = lastUpSeen[rec.hash]
        val upRate =
                if (prevUp == null) 0L
                else (uploaded - prevUp.second).coerceAtLeast(0) * 1000 / dt
        lastUpSeen[rec.hash] = now to uploaded

        val snapName = row?.name?.takeIf { it.isNotBlank() }
        return Torrent(
                hash = rec.hash,
                name = snapName ?: info?.effectiveName() ?: rec.name,
                saveDir = rec.saveDir,
                status = status,
                sizeBytes = (row?.size ?: 0L).takeIf { it > 0L }
                                ?: info?.size ?: base?.sizeBytes ?: 0L,
                downloadedBytes = downloaded,
                uploadedBytes = uploaded,
                progress = progress,
                pieceCount = (row?.pieces?.toInt() ?: 0).takeIf { it > 0 }
                                ?: info?.piece_count?.toInt() ?: base?.pieceCount ?: 0,
                havePieces = havePieces,
                pieceLength = info?.piece_length ?: base?.pieceLength ?: 0L,
                isPrivate = info?.`private` ?: base?.isPrivate ?: false,
                metadataReady = metadataReady,
                addedAt = rec.addedAt,
                createdAt = info?.creation_date?.times(1000),
                createdBy = info?.created_by,
                comment = info?.comment,
                kind = info?.kind ?: rec.kind,
                trackers = buildTrackers(rec.hash, info, rec, base),
                // Prefer the FRESH metainfo mirror (infoCache) — it is
                // updated when metadata arrives. Falling back to the previous
                // frame's list made `files` permanently empty when the first
                // frame was built before the mirror existed (e.g. a magnet
                // resolved in the add dialog): `base?.files` was `[]` and the
                // `?:` short-circuit never let the real list through.
                files = info?.files.orEmpty()
                                .map { com.typebit.model.FileEntry(it.path, it.length, it.renamed) }
                                .ifEmpty { base?.files.orEmpty() },
                seeds = base?.seeds ?: 0,
                peers = base?.peers ?: 0,
                downSpeed = downRate,
                upSpeed = upRate,
                completedAt = base?.completedAt,
                category = rec.category,
                tags = rec.tags,
                haveBitsHex = row?.hx ?: base?.haveBitsHex.orEmpty(),
                filePriorities = rec.filePriorities,
        )
    }

    /**
     * The tracker list shown in the detail tab. The engine session owns the
     * announce list — it starts from the metainfo and is then mutated by
     * [addTracker]/[removeTracker] — so it is the source of truth; the metainfo
     * plus the record only stand in while the engine cannot answer. [base]
     * supplies the last known per-URL state (status, seeds, …).
     */
    private fun buildTrackers(
            hash: String,
            info: TorrentInfoDto?,
            rec: TorrentRecord,
            base: Torrent?
    ): List<TrackerInfo> {
        val known = base?.trackers.orEmpty().associateBy { it.url }
        val live = engine.trackers(hash)?.takeIf { it.isNotEmpty() }
        val urls =
                live
                        ?: (info?.announce_list.orEmpty().flatten().filterNot {
                            it in rec.removedTrackers
                        } + rec.trackers)
                                .distinct()
        return urls.map { url -> known[url] ?: TrackerInfo(url = url) }
    }

    // ---- persistence helpers ----

    private fun reAddRecord(rec: TorrentRecord) {
        val hash =
                when {
                    // A magnet whose metadata was already fetched is re-added
                    // WITH its info dict (wrapped into a full torrent file) —
                    // it never re-fetches metadata.
                    rec.kind == "MAGNET" && rec.infoBase64.isNotBlank() -> {
                        val bytes = B64.decode(rec.infoBase64)
                        if (bytes == null) null
                        else engine.addTorrent(wrapInfoDict(bytes), rec.saveDir, rec.filePriorities)
                    }
                    rec.kind == "MAGNET" -> engine.addMagnet(rec.data, rec.saveDir)
                    else -> {
                        val bytes = B64.decode(rec.data)
                        if (bytes == null) null
                        else engine.addTorrent(bytes, rec.saveDir, rec.filePriorities)
                    }
                }
        if (hash == null) {
            _state.update { it.copy(lastError = "恢复失败：${rec.name}") }
            return
        }
        // Re-apply runtime-added trackers (they are not part of the engine's
        // saved state, so the app-level record is the source of truth).
        for (t in rec.trackers) {
            engine.addTracker(hash, t)
        }
        for (t in rec.removedTrackers) {
            engine.removeTracker(hash, t)
        }
        // Magnet priorities are applied once refreshStats sees metadata.
        if (rec.kind != "MAGNET" && rec.filePriorities.isNotEmpty()) {
            applyPriorities(hash, rec.filePriorities)
        }
        // File renames are app-level data: re-apply them so the staged path
        // bookkeeping and the final promotion keep working after a restart.
        if (rec.renames.isNotEmpty()) {
            applyRenames(hash, rec.renames)
        }
        // Refresh the mirror so renamed names show immediately.
        engine.torrentInfo(hash)?.let { infoCache[hash] = it }
    }

    /**
     * Wraps a raw bencoded `info` dict into a full single-file torrent
     * (`d4:info<info>e`) so it can be re-added via [TorrentEngine.addTorrent].
     * This is exactly how `typebit::metainfo::Torrent::from_info` builds it.
     */
    private fun wrapInfoDict(info: ByteArray): ByteArray {
        val prefix = "d4:info".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArray(prefix.size + info.size + 1)
        prefix.copyInto(out)
        info.copyInto(out, prefix.size)
        out[out.size - 1] = 'e'.code.toByte()
        return out
    }

    /** Re-applies persisted per-file renames to an engine torrent. */
    private fun applyRenames(hash: String, renames: Map<Int, String>) {
        for ((index, name) in renames) {
            engine.renameFile(hash, index, name)
        }
    }

    /** Applies per-file priorities to an engine torrent (index-aligned). */
    private fun applyPriorities(hash: String, priorities: List<Int>) {
        for ((index, p) in priorities.withIndex()) {
            if (p != 1) engine.setFilePriority(hash, index, p)
        }
    }

    /**
     * Renames one file of a torrent. The engine keeps writing to the original staged path and
     * promotes the renamed name on completion; the new name is persisted on the record so it
     * survives restarts.
     */
    /**
     * Live peer list for the Peers tab (best-effort, from the engine).
     *
     * Runs on a dedicated single-thread executor ([peersScope]) so a slow reply can NEVER block the
     * poll loop or a download action on [engineScope]. The native worker serializes commands
     * internally, so concurrent queries from both scopes are safe (the JNI handle is a shared,
     * `Send + Sync` reference).
     */
    suspend fun peers(hash: String): List<com.typebit.engine.PeerDto> =
            withContext(peersScope.coroutineContext) {
                // Never throws: this runs inside the Peers tab's polling
                // LaunchedEffect, and an exception escaping it cancels the
                // composition scope — i.e. an app crash on Android.
                runCatching {
                            if (engine.isRunning) engine.peers(hash) else emptyList()
                        }
                        .getOrDefault(emptyList())
            }

    /**
     * Engine-wide statistics for the stats dialog. Like [peers], runs off the
     * dedicated executor so a slow reply never blocks the poll loop.
     */
    suspend fun fetchStats(): com.typebit.engine.EngineStatsDto =
            withContext(peersScope.coroutineContext) {
                runCatching {
                            if (engine.isRunning) engine.stats()
                            else com.typebit.engine.EngineStatsDto()
                        }
                        .getOrDefault(com.typebit.engine.EngineStatsDto())
            }

    fun renameFile(hash: String, file: Int, name: String) = onEngine {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@onEngine
        if (engine.renameFile(hash, file, trimmed)) {
            engine.torrentInfo(hash)?.let { infoCache[hash] = it }
            records =
                    records.map { r ->
                        if (r.hash == hash) r.copy(renames = r.renames + (file to trimmed)) else r
                    }
            persistRecords()
            refreshStats()
        } else {
            _state.update { it.copy(lastError = "重命名失败：名称无效（不能含 .. 或绝对路径）") }
        }
    }

    private fun setRecordPaused(hash: String, paused: Boolean) {
        records = records.map { if (it.hash == hash) it.copy(paused = paused) else it }
        persistRecords()
    }

    private fun persistRecords() {
        torrentRepo.saveRecords(records)
    }

    private fun persistResume() {
        // Guard: `stop()` can run before the async boot finished creating
        // the engine (quick app exit) — the native handle would be 0.
        if (!engine.isRunning) return
        engine.saveState()?.let { torrentRepo.saveResumeState(it) }
    }

    private fun applyLimits(settings: AppSettings) {
        val (down, up) = effectiveLimits(settings.speed)
        engine.setGlobalLimits(down, up)
        lastAppliedLimits = down to up
    }

    /** Effective (down, up) byte-per-second limits, honoring the schedule. */
    private fun effectiveLimits(speed: com.typebit.data.SpeedSettings): Pair<Long, Long> {
        val active =
                if (speed.alternativeLimitsEnabled && speed.scheduleEnabled && scheduleOpen(speed)
                ) {
                    speed.altDownloadLimitKib to speed.altUploadLimitKib
                } else {
                    speed.globalDownloadLimitKib to speed.globalUploadLimitKib
                }
        return active.first * 1024 to active.second * 1024
    }

    /** Whether the alternative-limit schedule window is currently open. */
    private fun scheduleOpen(speed: com.typebit.data.SpeedSettings): Boolean {
        val now =
                kotlinx.datetime.Clock.System.now()
                        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        val dayOk =
                when (speed.scheduleDays) {
                    com.typebit.data.ScheduleDays.EVERY_DAY -> true
                    com.typebit.data.ScheduleDays.WEEKDAYS -> now.dayOfWeek.isoDayNumber in 1..5
                    com.typebit.data.ScheduleDays.WEEKEND -> now.dayOfWeek.isoDayNumber in 6..7
                    com.typebit.data.ScheduleDays.MONDAY ->
                            now.dayOfWeek == kotlinx.datetime.DayOfWeek.MONDAY
                    com.typebit.data.ScheduleDays.TUESDAY ->
                            now.dayOfWeek == kotlinx.datetime.DayOfWeek.TUESDAY
                    com.typebit.data.ScheduleDays.WEDNESDAY ->
                            now.dayOfWeek == kotlinx.datetime.DayOfWeek.WEDNESDAY
                    com.typebit.data.ScheduleDays.THURSDAY ->
                            now.dayOfWeek == kotlinx.datetime.DayOfWeek.THURSDAY
                    com.typebit.data.ScheduleDays.FRIDAY ->
                            now.dayOfWeek == kotlinx.datetime.DayOfWeek.FRIDAY
                    com.typebit.data.ScheduleDays.SATURDAY ->
                            now.dayOfWeek == kotlinx.datetime.DayOfWeek.SATURDAY
                    com.typebit.data.ScheduleDays.SUNDAY ->
                            now.dayOfWeek == kotlinx.datetime.DayOfWeek.SUNDAY
                }
        if (!dayOk) return false
        val minutes = now.hour * 60 + now.minute
        val from = speed.scheduleFromHour * 60 + speed.scheduleFromMinute
        val to = speed.scheduleToHour * 60 + speed.scheduleToMinute
        return if (from <= to) minutes in from until to else minutes >= from || minutes < to
    }

    private fun effectiveSaveDir(): String =
            Platform.resolveSaveDir(_state.value.settings.downloads.defaultSavePath)

    private fun buildCategories(): List<String> {
        val set = LinkedHashSet<String>()
        set.add("未分类")
        records.forEach { if (it.category.isNotBlank()) set.add(it.category) }
        _state.value.settings.downloads.categorySavePaths.keys.forEach { set.add(it) }
        return set.toList()
    }

    private fun buildTags(): List<String> {
        val set = LinkedHashSet<String>()
        records.forEach { set.addAll(it.tags) }
        return set.toList()
    }
}
