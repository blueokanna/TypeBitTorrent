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

private const val COMMUNITY_TRACKERS_URL = "https://cf.trackerslist.com/best.txt"

private const val TRACKER_UPDATE_POLL_MS = 5 * 60 * 1000L

private const val TRACKER_UPDATE_TIMEOUT_MS = 15_000L

private const val MAX_SUBSCRIPTION_URLS = 5

/**
 * Single source of truth for the UI (UI → action → engine/persistence → [state]); the engine runs
 * on its own Rust thread while the poll loop here drains events and persists resume data.
 *
 * Every engine call is a blocking JNI round-trip, so all of them run on the private single-threaded
 * [engineScope], never the UI thread; `limitedParallelism(1)` also serializes actions against the
 * poll loop, keeping the bookkeeping race-free.
 */
class AppStore(
        private val engine: TorrentEngine,
        private val settingsRepo: SettingsRepository,
        private val torrentRepo: TorrentRepository,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

/**
 * Last-resort net for store-side failures: an uncaught exception here reaches the thread's default
 * handler, which on Android kills the process.
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

    // Deliberately separate from [engineScope]: a slow `nativePeers` round-trip must never stall
    // the poll loop, and `limitedParallelism(1)` keeps blocking threads from piling up.
    private var peersScope: CoroutineScope = newPeersScope()

    private fun newPeersScope(): CoroutineScope =
            CoroutineScope(
                    SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
                            storeFailureHandler
            )

    // Windows system actions call out to `netsh` / `powershell` and can take seconds; they must
    // never occupy the serialized engine executor, and `IO` cannot pile up for occasional actions.
    private var systemScope: CoroutineScope =
            CoroutineScope(
                    SupervisorJob() + Dispatchers.IO.limitedParallelism(2) + storeFailureHandler
            )

    /** Engine teardown is JNI + disk work, so it must never run on the caller's thread. */
    private var shutdownScope: CoroutineScope =
            CoroutineScope(
                    SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + storeFailureHandler
            )

    /** Stops the engine (cancelling [engineScope]) and then boots a fresh one, so it owns this scope. */
    private fun newRestartScope(): CoroutineScope =
            CoroutineScope(
                    SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + storeFailureHandler
            )

    private var restartScope: CoroutineScope = newRestartScope()

    /** Set while shutting down, cleared by [start]; a pending restart reads it so a window close wins. */
    @Volatile private var shutdownBegan = false

    private var trackerUpdateJob: Job? = null

    private var recheckJob: Job? = null

    private val trackerUpdateLock = Mutex()

    // App-level records (category/tags/source); touched only from [engineScope].
    private var records: List<TorrentRecord> = emptyList()

    /** Proof-of-download receipts persisted in `<appData>/receipts/`. */
    private val receiptRepo = ReceiptRepository()

    /** Metainfo mirror cache; refetched only when metadata arrives. */
    private val infoCache = HashMap<String, TorrentInfoDto>()

    // Speed bookkeeping: (poll time, downloaded/uploaded bytes) per hash.
    private val lastSeen = HashMap<String, Pair<Long, Long>>()
    private val lastUpSeen = HashMap<String, Pair<Long, Long>>()
    private var lastTotals: Pair<Long, Long>? = null
    private var lastGlobalPoll = 0L

    private var pollJob: Job? = null

    // Guards `start()` against racing itself: `engineRunning` only flips true at the END of boot.
    private var bootJob: Job? = null

    /** Guards [teardown] against concurrent `stop()` / `stopBlocking()`. */
    private val teardownLock = Any()
    private var stopping = false

    /** Records retargeted from an unwritable save dir during the last boot. */
    private var retargetedRecords = 0

    private var lastSaveAt = 0L

    // Native-applied settings, diffed so an edit only crosses JNI when a relevant value changed.
    private var lastAppliedLimits: Pair<Long, Long>? = null
    private var lastAppliedSessionConfig: String? = null
    private var lastAppliedExtraTrackers: Set<String> = emptySet()
    private var settingsSaveJob: Job? = null

    /** Runs [block] on the engine executor (off the UI thread, serialized). */
    private fun onEngine(block: suspend () -> Unit) {
        engineScope.launch { block() }
    }

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
        // Off the engine thread on purpose: a slow fetch here would delay every restore behind it.
        startTrackerUpdates()
    }

    /** Periodic refresh on [systemScope]: a dead URL must never stall the engine. */
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
     * One pass; `force` ignores the interval. Failures keep the previous list so the next poll
     * retries, and [trackerUpdateLock] serializes concurrent fetches.
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
                // Re-read the latest settings: a save that landed during the fetch must not be
                // reverted by this pass writing a stale snapshot.
                var effective: AppSettings? = null
                _state.update { state ->
                    if (subscriptionUrls(state.settings.bitTorrent) != urls) {

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
                // applySettings diffs against what is applied, pushes only the new URLs, persists.
                onEngine { applySettings(applied) }
                (fetched - previous).size
            }

    private fun subscriptionUrls(bt: com.typebit.data.BitTorrentSettings): List<String> {
        val configured = parseTrackerLines(bt.trackerUpdateUrl).toList()
        return configured.ifEmpty { listOf(COMMUNITY_TRACKERS_URL) }
    }

    /** Fire-and-forget: a dead URL can take 15 s per host to time out. */
    fun requestTrackerSubscriptionRefresh() {
        if (!systemScope.isActive) return
        systemScope.launch { runCatching { refreshTrackerSubscription(force = true) } }
    }

    private fun parseTrackerLines(raw: String): Set<String> =
            raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun trackersSet(settings: AppSettings): Set<String> =
            settings.bitTorrent
                    .allTrackers
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()

    private fun onEngineJob(block: suspend () -> Unit): Job = engineScope.launch { block() }

    /**
     * Hashes on-disk data into the engine, which otherwise starts every session with an empty
     * bitfield (an already-complete torrent would be re-downloaded and never seed). Blocking.
     */
    fun recheck(hash: String) {
        if (recheckJob?.isActive == true) {
            _state.update { it.copy(recheckMessage = "已有校验在进行中，请稍候") }
            return
        }
        val name = _state.value.torrents.firstOrNull { it.hash == hash }?.name ?: hash
        _state.update {
            it.copy(
                    recheckHash = hash,
                    recheckRunning = true,
                    recheckDone = 0L,
                    recheckTotal = 0L,
                    recheckMessage = "正在校验「$name」的本地数据…",
            )
        }
        recheckJob =
                systemScope.launch {
                    // Adds and rechecks are queued separately, so the engine may not know it yet.
                    if (!awaitTorrentKnown(hash)) {
                        _state.update {
                            it.copy(
                                    recheckRunning = false,
                                    recheckHash = "",
                                    recheckMessage = "无法校验「$name」：任务尚未进入引擎",
                            )
                        }
                        return@launch
                    }
                    val verified = engine.recheckData(hash)
                    val progress = engine.recheckProgress()
                    val total = progress.totalBytes
                    val message =
                            when {
                                verified < 0 -> "校验失败：「$name」的数据无法读取或元数据缺失"
                                progress.cancelled -> "已取消校验「$name」"
                                verified == 0 -> "「$name」在磁盘上没有找到可用数据（已下载 0 块）"
                                else ->
                                    "已校验「$name」：$verified 块可用" +
                                        (if (total > 0) "（已核对 ${total / 1024 / 1024} MiB）" else "")
                            }
                    _state.update {
                        it.copy(
                                recheckRunning = false,
                                recheckHash = "",
                                recheckDone = total,
                                recheckTotal = total,
                                recheckMessage = message,
                        )
                    }
                    refreshStats()
                }
    }

    fun cancelRecheck(): Boolean = engine.cancelRecheck()

    /** Bounded wait: the add path is asynchronous, and hashing before the session exists is useless. */
    private suspend fun awaitTorrentKnown(hash: String, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (engine.torrentInfo(hash) != null) return true
            delay(200)
        }
        return false
    }

    /** Non-blocking teardown: the JNI join and disk flushes run on [shutdownScope], never the caller's thread. */
    fun stop() {
        shutdownBegan = true
        teardown(blocking = false)
    }

    /** Blocking variant for process-exit paths, where the engine must be joined deterministically. */
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

        // Persistence is best-effort and must never skip the engine teardown: a leaked worker would
        // let the next start spawn a second engine racing on the same port and `.part` files.
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

    /** Blocking JNI parse; call off the UI thread. */
    fun parseTorrentFile(bytes: ByteArray): com.typebit.engine.TorrentInfoDto? =
            engine.parseTorrent(bytes)

    /** Blocking; `pieceLength` must be a power of two in 16 KiB..256 MiB. */
    fun makeTorrent(options: com.typebit.engine.MakeTorrentOptions): ByteArray? =
            engine.makeTorrent(options)

    fun makeTorrentProgress(): com.typebit.engine.MakeTorrentProgress =
            engine.makeTorrentProgress()

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

    fun start(hash: String) = resume(hash)

    /** Optimistic UI: the status flips immediately; the engine, then the poll tick, catch up. */
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

    /** Display name only: files keep their original paths, while share dialogs carry `dn=`. */
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

    fun magnetLink(hash: String, name: String): String {
        val dn = name.ifBlank { hash }
        return "magnet:?xt=urn:btih:$hash&dn=$dn"
    }

    /** Signs `[0, downloadedBytes]` over the unix window `[addedAtMs, now]`; the JSON is persisted. */
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

    /** Ed25519 + structural check; `ok=false` for forged, tampered or malformed receipts. */
    suspend fun verifyReceiptJson(json: String): ReceiptVerifyResultDto =
        withContext(engineScope.coroutineContext) {
            engine.verifyReceipt(json)
        }

    fun listReceipts(): List<ReceiptFile> = receiptRepo.list()

    fun listReceiptsFor(hash: String): List<ReceiptFile> = receiptRepo.listFor(hash)

    fun deleteReceipt(path: String): Boolean = receiptRepo.delete(path)

    fun readReceipt(path: String): String? = receiptRepo.read(path)

    /** Phase 1: adds the magnet already started, so the engine fetches metadata, and persists the record. */
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

    /** Phase 2a: waits off the engine executor for the metadata mirror; null on timeout. */
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
     * Phase 2b: applies per-file priorities to the resolved magnet and persists the raw `info` dict
     * so a restart never re-fetches metadata; pauses afterwards when `paused` was chosen.
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
     * Releases the data hold so a magnet whose metadata is slow keeps downloading with the default
     * selection instead of being removed when the dialog times out.
     */
    fun releaseMagnetPending(hash: String) = onEngine {
        engine.setHoldData(hash, hold = false)
        records = records.map { if (it.hash == hash) it.copy(pendingSelection = false) else it }
        persistRecords()
        refreshStats()
    }

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

    /** Atomic `setFilePriorities` instead of N per-file calls: one JNI round-trip, one hold release. */
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

    /** UI state updates immediately; disk I/O and engine calls are diffed and coalesced on the executor. */
    fun updateSettings(settings: AppSettings) {
        _state.update { it.copy(settings = settings) }
        onEngine { applySettings(settings) }
    }

    /** Creation-time fields ([EngineConfigJson.engineConfig]) need an engine rebuild; the rest applies live. */
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
     * Rebuilds the engine in place: the teardown flushes resume data, settings and records, and
     * [start] re-adds every record — the same event as an app restart.
     */
    fun restartEngine() {
        if (!restartScope.isActive) restartScope = newRestartScope()
        restartScope.launch {
            if (shutdownBegan) return@launch
            teardown(blocking = true)
            // An exit that began during teardown wins: booting now would leak the native worker and
            // race the next start on the same port and `.part` files.
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

    /** Bound port, or the configured value when not listening. */
    private fun listenPort(): Int =
        _state.value.listenPort.takeIf { it > 0 }
            ?: if (_state.value.settings.connection.useRandomPort) 0
            else _state.value.settings.connection.listenPort

    /** Runs off the engine thread; the result lands in `state.systemOk` / `systemMessage`. */
    private fun runSystemAction(block: suspend () -> com.typebit.engine.SystemResultDto) {
        systemScope.launch {
            val r = block()
            _state.update { it.copy(systemOk = r.ok, systemMessage = r.message) }
        }
    }

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

    fun removeFirewallRules() {
        val port = listenPort()
        if (port <= 0) return
        runSystemAction { engine.firewallRemove(port) }
    }

    fun refreshFirewallStatus() {
        val port = listenPort()
        if (port <= 0) return
        runSystemAction { engine.firewallStatus(port) }
    }

    fun configureIcs() = runSystemAction { engine.icsEnable() }

    fun disableIcs() = runSystemAction { engine.icsDisable() }

    fun refreshIcsStatus() = runSystemAction { engine.icsStatus() }

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

    /** Aggregates per-torrent deltas before applying them; true = the state must be persisted now. */
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

    /** One batched snapshot per tick; metainfo (and full state) is refetched only for new metadata. */
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
            // Only polled while a pass runs, so the tick costs at most one extra JNI call.
            val recheck = if (recheckJob?.isActive == true) engine.recheckProgress() else null
            s.copy(
                    torrents = updated,
                    globalDownRate = downRate.coerceAtLeast(0),
                    globalUpRate = upRate.coerceAtLeast(0),
                    totalDownloaded = totals.first,
                    totalUploaded = totals.second,
                    recheckDone = recheck?.doneBytes ?: s.recheckDone,
                    recheckTotal = recheck?.totalBytes ?: s.recheckTotal,
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

    /** Status is deterministic — paused wins, then complete, then metadata availability. */
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
                // Prefer the fresh metainfo mirror: an earlier frame's list can be empty when that
                // frame was built before the mirror existed (magnet resolved in the add dialog).
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
     * The engine session owns the announce list (seeded from the metainfo, then mutated by tracker
     * edits), so it is the source of truth; [base] supplies the last known per-URL state.
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

    private fun reAddRecord(rec: TorrentRecord) {
        val hash =
                when {
                    // A magnet whose metadata is known is re-added wrapped, so it never re-fetches.
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
        // Runtime-added trackers are not in the engine's saved state: re-apply them from the record.
        for (t in rec.trackers) {
            engine.addTracker(hash, t)
        }
        for (t in rec.removedTrackers) {
            engine.removeTracker(hash, t)
        }
        if (rec.kind != "MAGNET" && rec.filePriorities.isNotEmpty()) {
            applyPriorities(hash, rec.filePriorities)
        }
        // File renames are app-level data: re-apply so staged-path bookkeeping survives a restart.
        if (rec.renames.isNotEmpty()) {
            applyRenames(hash, rec.renames)
        }
        engine.torrentInfo(hash)?.let { infoCache[hash] = it }
    }

    /** Wraps a raw bencoded `info` dict as `d4:info<info>e`, exactly as `typebit::metainfo::Torrent::from_info`. */
    private fun wrapInfoDict(info: ByteArray): ByteArray {
        val prefix = "d4:info".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArray(prefix.size + info.size + 1)
        prefix.copyInto(out)
        info.copyInto(out, prefix.size)
        out[out.size - 1] = 'e'.code.toByte()
        return out
    }

    private fun applyRenames(hash: String, renames: Map<Int, String>) {
        for ((index, name) in renames) {
            engine.renameFile(hash, index, name)
        }
    }

    private fun applyPriorities(hash: String, priorities: List<Int>) {
        for ((index, p) in priorities.withIndex()) {
            if (p != 1) engine.setFilePriority(hash, index, p)
        }
    }

    /** The engine keeps writing to the original staged path and promotes the new name on completion. */
    /**
     * Best-effort live peer list on [peersScope], so a slow reply never blocks the poll loop;
     * concurrent engine queries are safe (the JNI handle is a shared `Send + Sync` reference).
     */
    suspend fun peers(hash: String): List<com.typebit.engine.PeerDto> =
            withContext(peersScope.coroutineContext) {
                // Never throws: an escaping exception would cancel the Peers tab's polling scope.
                runCatching {
                            if (engine.isRunning) engine.peers(hash) else emptyList()
                        }
                        .getOrDefault(emptyList())
            }

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
        // Guard: `stop()` can run before boot finished creating the engine, so the handle is still 0.
        if (!engine.isRunning) return
        engine.saveState()?.let { torrentRepo.saveResumeState(it) }
    }

    private fun applyLimits(settings: AppSettings) {
        val (down, up) = effectiveLimits(settings.speed)
        engine.setGlobalLimits(down, up)
        lastAppliedLimits = down to up
    }

    /** Effective (down, up) byte-per-second limits, after the schedule. */
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

    private fun scheduleOpen(speed: com.typebit.data.SpeedSettings): Boolean {
        val now =
                kotlin.time.Clock.System.now()
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
