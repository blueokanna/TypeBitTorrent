package com.typebit.store

import com.typebit.data.AppSettings
import com.typebit.data.EngineConfigJson
import com.typebit.data.SettingsRepository
import com.typebit.data.TorrentRepository
import com.typebit.engine.EngineEventDto
import com.typebit.engine.TorrentEngine
import com.typebit.engine.TorrentInfoDto
import com.typebit.engine.TorrentSnapshotDto
import com.typebit.model.Torrent
import com.typebit.model.TorrentFilter
import com.typebit.model.TorrentRecord
import com.typebit.model.TorrentStatus
import com.typebit.model.TrackerInfo
import com.typebit.platform.Platform
import com.typebit.util.B64
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/**
 * The single source of truth for the UI.
 *
 * Unidirectional data flow: UI → action → (engine + persistence) → [state].
 * The engine runs on its own Rust thread; the store owns a poll loop that
 * drains events, refreshes stats and periodically persists resume data.
 *
 * Performance contract: every engine call is a blocking JNI round-trip, so
 * ALL of it runs on a private single-threaded background executor
 * ([engineScope]) — never on the UI thread. Doing it on the main thread was
 * the source of the settings jank and the unresponsive pause/resume buttons
 * (a blocked JNI reply froze the click handler; the state only caught up
 * after navigating away and back). `limitedParallelism(1)` also serializes
 * actions against the poll loop, so the state bookkeeping is race-free.
 */
class AppStore(
    private val engine: TorrentEngine,
    private val settingsRepo: SettingsRepository,
    private val torrentRepo: TorrentRepository,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private fun newEngineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private var engineScope: CoroutineScope = newEngineScope()

    // Persisted app-level records (engine cannot carry category/tags/source).
    // Only touched from [engineScope] — never from the UI thread.
    private var records: List<TorrentRecord> = emptyList()

    /** Full metainfo mirror cache; refetched only when metadata arrives. */
    private val infoCache = HashMap<String, TorrentInfoDto>()

    // Speed bookkeeping: (poll time, downloaded bytes) per hash.
    private val lastSeen = HashMap<String, Pair<Long, Long>>()
    private var lastTotals: Pair<Long, Long>? = null
    private var lastGlobalPoll = 0L

    private var pollJob: Job? = null
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
        if (_state.value.engineRunning) return
        if (!engineScope.isActive) engineScope = newEngineScope()
        onEngine { boot() }
    }

    private suspend fun boot() {
        val settings = settingsRepo.load()
        val saveDir = settings.downloads.defaultSavePath
            .ifBlank { Platform.defaultDownloadDir() }
        val started = engine.start(EngineConfigJson.engineConfig(settings), saveDir)
        if (!started) {
            _state.update { it.copy(lastError = "引擎启动失败：原生库未加载") }
            return
        }

        // Restore app-level torrent records.
        records = torrentRepo.loadRecords()
        for (rec in records) {
            reAddRecord(rec)
        }
        // Pre-populate the info cache so the first tick renders full rows;
        // it is refetched whenever the snapshot reports new metadata.
        for (rec in records) {
            engine.torrentInfo(rec.hash)?.let { infoCache[rec.hash] = it }
        }
        // Restore verified-piece bitfields + DHT table.
        torrentRepo.loadResumeState()?.let { engine.loadState(it) }
        // Start torrents that were not paused.
        for (rec in records) {
            if (!rec.paused) engine.start(rec.hash)
        }
        // Apply speed limits.
        applyLimits(settings)

        _state.update {
            it.copy(
                settings = settings,
                engineRunning = true,
                peerId = engine.peerId(),
                categories = buildCategories(),
                tags = buildTags(),
            )
        }
        refreshStats()

        pollJob = onEngineJob { pollLoop() }
    }

    private fun onEngineJob(block: suspend () -> Unit): Job =
        engineScope.launch { block() }

    /**
     * Stops the engine and flushes persistence. Runs the shutdown work on
     * the engine executor but waits for it with a bounded timeout so the
     * data survives process exit (the executor's threads are daemons on the
     * JVM, so a pure fire-and-forget shutdown could be cut off mid-write).
     */
    fun stop() {
        pollJob?.cancel()
        engineScope.cancel()
        runBlocking {
            withTimeoutOrNull(5_000) {
                settingsSaveJob?.cancel()
                settingsRepo.save(_state.value.settings)
                persistResume()
                engine.stop()
            }
        }
        _state.update { it.copy(engineRunning = false) }
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    /**
     * Parses `.torrent` bytes without adding — add-dialog preview.
     * Blocking JNI parse; callers should run it off the UI thread.
     */
    fun parseTorrentFile(bytes: ByteArray): com.typebit.engine.TorrentInfoDto? =
        engine.parseTorrent(bytes)

    fun addTorrentFile(bytes: ByteArray, fileName: String) {
        val s = _state.value.settings
        addTorrentFileEx(
            bytes = bytes,
            fileName = fileName,
            saveDir = s.downloads.defaultSavePath.ifBlank { Platform.defaultDownloadDir() },
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
        val hash = engine.addTorrent(bytes, saveDir, filePriorities) ?: run {
            _state.update { it.copy(lastError = "无法解析种子文件：$fileName") }
            return@onEngine
        }
        val info = engine.torrentInfo(hash)
        if (info != null) infoCache[hash] = info
        val record = TorrentRecord(
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
            saveDir = s.downloads.defaultSavePath.ifBlank { Platform.defaultDownloadDir() },
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
        val hash = engine.addMagnet(trimmed, saveDir) ?: run {
            _state.update { it.copy(lastError = "无法解析磁力链接") }
            return@onEngine
        }
        val info = engine.torrentInfo(hash)
        if (info != null) infoCache[hash] = info
        val record = TorrentRecord(
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
        // File priorities can only be applied once the metadata arrives;
        // refreshStats does that when the snapshot reports `meta` flips.
        if (!record.paused) engine.start(hash)
        refreshStats()
    }

    /** Starts or resumes a torrent. */
    fun start(hash: String) = resume(hash)

    /**
     * Pauses a torrent. The status flips to PAUSED immediately (optimistic
     * UI), then the engine + records are updated on the background executor;
     * the poll tick confirms the authoritative state. Never blocks the UI.
     */
    fun pause(hash: String) {
        _state.update { s ->
            s.copy(
                torrents = s.torrents.map { t ->
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
                torrents = s.torrents.map { t ->
                    if (t.hash == hash && t.status == TorrentStatus.PAUSED) {
                        t.copy(status = if (t.isComplete) TorrentStatus.SEEDING else TorrentStatus.DOWNLOADING)
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
            records = records.filterNot { it.hash == hash }
            persistRecords()
            // Authoritative removal — also re-covers the (rare) case where a
            // poll tick between the optimistic update and this coroutine
            // rebuilt the row from the not-yet-updated records list.
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
     * Sets one file's download priority at runtime (0=Skip, 1=Normal,
     * 2=High) and persists it. Skipped files stop being requested.
     */
    fun setFilePriority(hash: String, file: Int, priority: Int) = onEngine {
        if (engine.setFilePriority(hash, file, priority)) {
            records = records.map { rec ->
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

    /** Adds a tracker URL to a running torrent and persists it. */
    fun addTracker(hash: String, url: String) = onEngine {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return@onEngine
        if (engine.addTracker(hash, trimmed)) {
            records = records.map { rec ->
                if (rec.hash == hash && trimmed !in rec.trackers) {
                    rec.copy(trackers = rec.trackers + trimmed)
                } else rec
            }
            persistRecords()
        }
    }

    /** Removes a tracker URL from a running torrent and persists it. */
    fun removeTracker(hash: String, url: String) = onEngine {
        if (engine.removeTracker(hash, url)) {
            records = records.map { rec ->
                if (rec.hash == hash) rec.copy(trackers = rec.trackers - url) else rec
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
     * Applies a settings edit. The UI state updates immediately; disk I/O
     * and engine calls happen on the background executor, diffed so they
     * only cross the JNI boundary when the relevant value changed, and the
     * JSON write is coalesced (rapid edits collapse into one save).
     */
    fun updateSettings(settings: AppSettings) {
        _state.update { it.copy(settings = settings) }
        onEngine { applySettings(settings) }
    }

    private suspend fun applySettings(settings: AppSettings) {
        // 1) Live speed limits — only when the effective value moved.
        val limits = effectiveLimits(settings.speed)
        if (limits != lastAppliedLimits) {
            engine.setGlobalLimits(limits.first, limits.second)
            lastAppliedLimits = limits
        }
        // 2) Session defaults for future torrents — only when changed.
        val cfg = EngineConfigJson.sessionConfig(settings)
        if (cfg != lastAppliedSessionConfig) {
            engine.setSessionConfig(cfg)
            lastAppliedSessionConfig = cfg
        }
        // 3) Newly imported extra trackers → add to ALL running torrents
        //    right away (the engine only reads the session config for
        //    torrents added afterwards, so a tracker-list import must be
        //    pushed to existing sessions explicitly).
        val trackersNow = settings.bitTorrent.extraTrackers.lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
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
        // 4) Persist, coalesced: a slider drag / keystroke storm becomes a
        //    single write after the input settles (plus the final save in
        //    [stop]).
        settingsSaveJob?.cancel()
        settingsSaveJob = onEngineJob {
            delay(400)
            settingsRepo.save(settings)
        }
    }

    fun setCategory(hash: String, category: String) = onEngine {
        records = records.map { if (it.hash == hash) it.copy(category = category) else it }
        persistRecords()
        _state.update {
            it.copy(
                categories = buildCategories(),
                torrents = it.torrents.map { t -> if (t.hash == hash) t.copy(category = category) else t },
            )
        }
    }

    fun toggleTag(hash: String, tag: String) = onEngine {
        records = records.map { r ->
            if (r.hash == hash) {
                val tags = if (tag in r.tags) r.tags - tag else r.tags + tag
                r.copy(tags = tags)
            } else r
        }
        persistRecords()
        _state.update {
            it.copy(
                tags = buildTags(),
                torrents = it.torrents.map { t ->
                    if (t.hash == hash) t.copy(tags = records.first { r -> r.hash == hash }.tags) else t
                },
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    // ------------------------------------------------------------------
    // poll loop
    // ------------------------------------------------------------------

    private suspend fun pollLoop() {
        while (engineScope.isActive && pollJob?.isActive == true) {
            val interval = _state.value.settings.behavior.refreshIntervalMs.coerceIn(200, 5000)
            delay(interval.toLong())

            // 1) Drain engine events first (cheap, authoritative).
            drainEvents(engine.takeEvents())

            // 2) Refresh per-torrent stats + global rates in ONE native
            //    snapshot call and ONE state update.
            refreshStats()

            // 3) Persist resume data on a slow cadence (like qBittorrent).
            val now = System.currentTimeMillis()
            if (now - lastSaveAt > 30_000) {
                lastSaveAt = now
                persistResume()
            }
        }
    }

    /**
     * Applies engine events without rebuilding the whole list per event:
     * deltas are aggregated per torrent first, then applied in one pass.
     */
    private fun drainEvents(events: List<EngineEventDto>) {
        if (events.isEmpty()) return
        _state.update { s ->
            var dht = s.dhtNodes
            var leechCount = s.antiLeechCount
            var leechClients = s.antiLeechClients
            var engineNotice: String? = null
            val antiLeechOn = s.settings.bitTorrent.antiLeechEnabled

            val peerAbs = HashMap<String, Int>()
            val peerAdj = HashMap<String, Int>()
            val pieceAdj = HashMap<String, Int>()
            val complete = HashSet<String>()
            val metadata = HashSet<String>()

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
                    9 -> if (antiLeechOn) {
                        leechCount++
                        val name = ev.c ?: "未知客户端"
                        if (name !in leechClients) {
                            leechClients = (leechClients + name).takeLast(20)
                        }
                    }
                    10 -> if (antiLeechOn) {
                        // Built-in anti-leech engine banned a peer (0.1.1).
                        leechCount++
                        val reason = when (ev.r) {
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
                        // typebit 0.1.3: non-fatal engine failure degraded
                        // operation — surface the real reason instead of a
                        // silent "0 B/s" (DHT/UDP trackers off, or DHT
                        // dormant because no router hostname resolved).
                        val msg = when (ev.code) {
                            0 -> "引擎：UDP 端口无法打开，DHT 与 UDP tracker 已停用（HTTP tracker 仍可用）"
                            1 -> "引擎：DHT 引导失败，无法解析引导路由器（DHT 休眠，tracker 不受影响）"
                            else -> "引擎：${ev.detail ?: "未知错误"}"
                        }
                        engineNotice = msg
                    }
                }
            }

            if (peerAbs.isEmpty() && peerAdj.isEmpty() && pieceAdj.isEmpty() &&
                complete.isEmpty() && metadata.isEmpty()
            ) {
                return@update s.copy(
                    dhtNodes = dht,
                    antiLeechCount = leechCount,
                    antiLeechClients = leechClients,
                    lastError = engineNotice ?: s.lastError,
                )
            }

            val torrents = s.torrents.map { t ->
                var out = t
                peerAbs[t.hash]?.let { out = out.copy(peers = it) }
                peerAdj[t.hash]?.let { out = out.copy(peers = (out.peers + it).coerceAtLeast(0)) }
                pieceAdj[t.hash]?.let {
                    out = out.copy(havePieces = (out.havePieces + it).coerceAtMost(out.pieceCount.coerceAtLeast(0)))
                }
                if (t.hash in complete) {
                    out = out.copy(status = TorrentStatus.SEEDING, progress = 1.0, completedAt = System.currentTimeMillis())
                }
                if (t.hash in metadata) out = out.copy(metadataReady = true)
                out
            }

            s.copy(
                dhtNodes = dht,
                torrents = torrents,
                antiLeechCount = leechCount,
                antiLeechClients = leechClients,
                lastError = engineNotice ?: s.lastError,
            )
        }
    }

    /**
     * Per-tick refresh driven by ONE batched native snapshot (DHT count,
     * global totals and every torrent's runtime stats). Full metainfo is
     * only refetched when the snapshot reports freshly-arrived metadata, so
     * the per-tick JNI traffic is constant regardless of torrent count.
     */
    private fun refreshStats() {
        val now = System.currentTimeMillis()
        val snap = engine.snapshot()
        val byHash = snap.torrents.associateBy { it.h }
        val totals = snap.totalsPair
        val dt = (now - lastGlobalPoll).coerceAtLeast(1L)
        val downRate = if (lastTotals == null) 0L else (totals.first - lastTotals!!.first) * 1000 / dt
        val upRate = if (lastTotals == null) 0L else (totals.second - lastTotals!!.second) * 1000 / dt
        lastTotals = totals
        lastGlobalPoll = now

        // Metadata arrived for a magnet → refresh the full mirror once, and
        // apply the persisted per-file priorities + renames now that files
        // are known (their indices only exist after the file table arrives).
        for (row in snap.torrents) {
            if (row.meta && infoCache[row.h]?.metadata_ready != true) {
                engine.torrentInfo(row.h)?.let { infoCache[row.h] = it }
                val rec = records.firstOrNull { it.hash == row.h }
                if (rec != null && rec.kind == "MAGNET") {
                    if (rec.filePriorities.isNotEmpty()) applyPriorities(row.h, rec.filePriorities)
                    if (rec.renames.isNotEmpty()) applyRenames(row.h, rec.renames)
                    engine.torrentInfo(row.h)?.let { infoCache[row.h] = it }
                }
            }
        }

        _state.update { s ->
            val updated = records.map { rec ->
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
            )
        }
    }

    /**
     * Rebuilds one display model from the snapshot row + the cached full
     * metainfo. The status is deterministic — paused wins, then complete
     * (seeding), then metadata availability — instead of the old heuristic
     * that guessed from stale progress deltas and made pause/resume appear
     * broken.
     */
    private fun buildTorrent(rec: TorrentRecord, base: Torrent?, row: TorrentSnapshotDto?, now: Long): Torrent {
        val info = infoCache[rec.hash]
        val paused = (row?.paused ?: false) || rec.paused
        val complete = row?.c ?: (base?.isComplete == true)
        val progress = row?.p ?: base?.progress ?: 0.0
        val downloaded = row?.d ?: base?.downloadedBytes ?: 0L
        val metadataReady = row?.meta ?: (info?.metadata_ready ?: base?.metadataReady ?: false)
        val havePieces = row?.have?.toInt() ?: base?.havePieces ?: 0

        val status = when {
            paused -> TorrentStatus.PAUSED
            complete -> TorrentStatus.SEEDING
            !metadataReady -> TorrentStatus.FETCHING_METADATA
            else -> TorrentStatus.DOWNLOADING
        }

        // Per-torrent download rate from byte deltas.
        val prev = lastSeen[rec.hash]
        val dt = (now - (prev?.first ?: now)).coerceAtLeast(1L)
        val downRate = if (prev == null) 0L else (downloaded - prev.second).coerceAtLeast(0) * 1000 / dt
        lastSeen[rec.hash] = now to downloaded

        val snapName = row?.name?.takeIf { it.isNotBlank() }
        return Torrent(
            hash = rec.hash,
            name = snapName ?: info?.effectiveName() ?: rec.name,
            saveDir = rec.saveDir,
            status = status,
            sizeBytes = (row?.size ?: 0L).takeIf { it > 0L } ?: info?.size ?: base?.sizeBytes ?: 0L,
            downloadedBytes = downloaded,
            uploadedBytes = 0L, // typebit 0.1.1 does not expose per-torrent uploads — see README
            progress = progress,
            pieceCount = (row?.pieces?.toInt() ?: 0).takeIf { it > 0 } ?: info?.piece_count?.toInt() ?: base?.pieceCount ?: 0,
            havePieces = havePieces,
            pieceLength = info?.piece_length ?: base?.pieceLength ?: 0L,
            isPrivate = info?.`private` ?: base?.isPrivate ?: false,
            metadataReady = metadataReady,
            addedAt = rec.addedAt,
            createdAt = info?.creation_date?.times(1000),
            createdBy = info?.created_by,
            comment = info?.comment,
            kind = info?.kind ?: rec.kind,
            trackers = buildTrackers(info, rec, base),
            files = base?.files
                ?: info?.files.orEmpty().map { com.typebit.model.FileEntry(it.path, it.length, it.renamed) },
            seeds = base?.seeds ?: 0,
            peers = base?.peers ?: 0,
            downSpeed = downRate,
            upSpeed = 0L,
            completedAt = base?.completedAt,
            category = rec.category,
            tags = rec.tags,
            haveBitsHex = row?.hx ?: base?.haveBitsHex.orEmpty(),
            filePriorities = rec.filePriorities,
        )
    }

    /**
     * The tracker list shown in the detail tab: the metainfo announce tiers
     * plus any runtime-added trackers persisted on the record. `base` (the
     * previous frame) already carries the merged list, so the merge only
     * runs when a frame is first built.
     */
    private fun buildTrackers(info: TorrentInfoDto?, rec: TorrentRecord, base: Torrent?): List<TrackerInfo> {
        val fromMeta = base?.trackers
            ?: info?.announce_list.orEmpty().flatten().map { TrackerInfo(url = it) }
        if (base != null || rec.trackers.isEmpty()) return fromMeta
        val known = fromMeta.mapTo(HashSet()) { it.url }
        return fromMeta + rec.trackers.filter { it !in known }.map { TrackerInfo(url = it) }
    }

    // ---- persistence helpers ----

    private fun reAddRecord(rec: TorrentRecord) {
        val hash = when (rec.kind) {
            "MAGNET" -> engine.addMagnet(rec.data, rec.saveDir)
            else -> {
                val bytes = B64.decode(rec.data)
                if (bytes == null) null else engine.addTorrent(bytes, rec.saveDir, rec.filePriorities)
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
     * Renames one file of a torrent. The engine keeps writing to the
     * original staged path and promotes the renamed name on completion;
     * the new name is persisted on the record so it survives restarts.
     */
    /** Live peer list for the Peers tab (best-effort, from the engine). */
    fun peers(hash: String): List<com.typebit.engine.PeerDto> =
        if (engine.isRunning) engine.peers(hash) else emptyList()

    fun renameFile(hash: String, file: Int, name: String) = onEngine {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@onEngine
        if (engine.renameFile(hash, file, trimmed)) {
            engine.torrentInfo(hash)?.let { infoCache[hash] = it }
            records = records.map { r ->
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
        val active = if (speed.alternativeLimitsEnabled && speed.scheduleEnabled && scheduleOpen(speed)) {
            speed.altDownloadLimitKib to speed.altUploadLimitKib
        } else {
            speed.globalDownloadLimitKib to speed.globalUploadLimitKib
        }
        return active.first * 1024 to active.second * 1024
    }

    /** Whether the alternative-limit schedule window is currently open. */
    private fun scheduleOpen(speed: com.typebit.data.SpeedSettings): Boolean {
        val now = kotlinx.datetime.Clock.System.now()
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        val dayOk = when (speed.scheduleDays) {
            com.typebit.data.ScheduleDays.EVERY_DAY -> true
            com.typebit.data.ScheduleDays.WEEKDAYS -> now.dayOfWeek.isoDayNumber in 1..5
            com.typebit.data.ScheduleDays.WEEKEND -> now.dayOfWeek.isoDayNumber in 6..7
            com.typebit.data.ScheduleDays.MONDAY -> now.dayOfWeek == kotlinx.datetime.DayOfWeek.MONDAY
            com.typebit.data.ScheduleDays.TUESDAY -> now.dayOfWeek == kotlinx.datetime.DayOfWeek.TUESDAY
            com.typebit.data.ScheduleDays.WEDNESDAY -> now.dayOfWeek == kotlinx.datetime.DayOfWeek.WEDNESDAY
            com.typebit.data.ScheduleDays.THURSDAY -> now.dayOfWeek == kotlinx.datetime.DayOfWeek.THURSDAY
            com.typebit.data.ScheduleDays.FRIDAY -> now.dayOfWeek == kotlinx.datetime.DayOfWeek.FRIDAY
            com.typebit.data.ScheduleDays.SATURDAY -> now.dayOfWeek == kotlinx.datetime.DayOfWeek.SATURDAY
            com.typebit.data.ScheduleDays.SUNDAY -> now.dayOfWeek == kotlinx.datetime.DayOfWeek.SUNDAY
        }
        if (!dayOk) return false
        val minutes = now.hour * 60 + now.minute
        val from = speed.scheduleFromHour * 60 + speed.scheduleFromMinute
        val to = speed.scheduleToHour * 60 + speed.scheduleToMinute
        return if (from <= to) minutes in from until to else minutes >= from || minutes < to
    }

    private fun effectiveSaveDir(): String {
        val d = _state.value.settings.downloads
        return d.defaultSavePath.ifBlank { Platform.defaultDownloadDir() }
    }

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
