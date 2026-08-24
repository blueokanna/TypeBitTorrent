package com.typebit.store

import com.typebit.data.AppSettings
import com.typebit.data.EngineConfigJson
import com.typebit.data.SettingsRepository
import com.typebit.data.TorrentRepository
import com.typebit.engine.EngineEventDto
import com.typebit.engine.TorrentEngine
import com.typebit.model.Torrent
import com.typebit.model.TorrentFilter
import com.typebit.model.TorrentRecord
import com.typebit.model.TorrentStatus
import com.typebit.model.TrackerInfo
import com.typebit.platform.Platform
import com.typebit.util.B64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/**
 * The single source of truth for the UI.
 *
 * Unidirectional data flow: UI → [dispatch] → (engine + persistence) →
 * [state]. The engine runs on its own Rust thread; the store owns a poll
 * loop that drains events, refreshes stats and periodically persists resume
 * data. Nothing outside this class mutates [state].
 */
class AppStore(
    private val engine: TorrentEngine,
    private val settingsRepo: SettingsRepository,
    private val torrentRepo: TorrentRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    // Persisted app-level records (engine cannot carry category/tags/source).
    private var records: List<TorrentRecord> = emptyList()

    // Speed bookkeeping: (poll time, downloaded bytes) per hash.
    private val lastSeen = HashMap<String, Pair<Long, Long>>()
    private var lastTotals: Pair<Long, Long>? = null
    private var lastGlobalPoll = 0L

    private var pollJob: Job? = null
    private var lastSaveAt = 0L

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    /** Boots the engine, restores state and starts the poll loop. */
    fun start() {
        if (_state.value.engineRunning) return
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

        pollJob = scope.launch { pollLoop() }
    }

    fun stop() {
        pollJob?.cancel()
        persistResume()
        engine.stop()
        _state.update { it.copy(engineRunning = false) }
    }

    // ------------------------------------------------------------------
    // actions
    // ------------------------------------------------------------------

    /** Parses `.torrent` bytes without adding — add-dialog preview. */
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
    ) {
        val hash = engine.addTorrent(bytes, saveDir) ?: run {
            _state.update { it.copy(lastError = "无法解析种子文件：$fileName") }
            return
        }
        val info = engine.torrentInfo(hash)
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
        )
    }

    /** Adds a magnet with the add-dialog options applied. */
    fun addMagnetEx(
        uri: String,
        saveDir: String,
        category: String,
        tags: List<String>,
        paused: Boolean,
    ) {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return
        val hash = engine.addMagnet(trimmed, saveDir) ?: run {
            _state.update { it.copy(lastError = "无法解析磁力链接") }
            return
        }
        val info = engine.torrentInfo(hash)
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
        )
        records = records + record
        persistRecords()
        if (!record.paused) engine.start(hash)
        refreshStats()
    }

    fun start(hash: String) {
        engine.start(hash)
        markPaused(hash, paused = false)
        refreshStats()
    }

    fun pause(hash: String) {
        engine.pause(hash)
        markPaused(hash, paused = true)
        refreshStats()
    }

    fun resume(hash: String) = start(hash)

    fun remove(hash: String) {
        engine.remove(hash)
        records = records.filterNot { it.hash == hash }
        persistRecords()
        _state.update {
            it.copy(
                torrents = it.torrents.filterNot { t -> t.hash == hash },
                selectedHash = if (it.selectedHash == hash) null else it.selectedHash,
            )
        }
    }

    fun select(hash: String?) {
        _state.update { it.copy(selectedHash = hash) }
    }

    fun setFilter(filter: TorrentFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun setSearch(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun updateSettings(settings: AppSettings) {
        _state.update { it.copy(settings = settings) }
        settingsRepo.save(settings)
        // Live-applied settings (the rest take effect at next engine start).
        applyLimits(settings)
        engine.setSessionConfig(EngineConfigJson.sessionConfig(settings))
    }

    fun setCategory(hash: String, category: String) {
        records = records.map { if (it.hash == hash) it.copy(category = category) else it }
        persistRecords()
        _state.update {
            it.copy(
                categories = buildCategories(),
                torrents = it.torrents.map { t -> if (t.hash == hash) t.copy(category = category) else t },
            )
        }
    }

    fun toggleTag(hash: String, tag: String) {
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
        while (scope.isActive && pollJob?.isActive == true) {
            val interval = _state.value.settings.behavior.refreshIntervalMs.coerceIn(200, 5000)
            delay(interval.toLong())

            // 1) Drain engine events first (cheap, authoritative).
            drainEvents(engine.takeEvents())

            // 2) Refresh per-torrent stats + global rates in ONE state
            //    update (fewer recompositions per poll tick).
            refreshStats()

            // 3) Persist resume data on a slow cadence (like qBittorrent).
            val now = System.currentTimeMillis()
            if (now - lastSaveAt > 30_000) {
                lastSaveAt = now
                persistResume()
            }
        }
    }

    private fun drainEvents(events: List<EngineEventDto>) {
        if (events.isEmpty()) return
        _state.update { s ->
            var dht = s.dhtNodes
            var torrents = s.torrents
            var leechCount = s.antiLeechCount
            var leechClients = s.antiLeechClients
            val antiLeechOn = s.settings.bitTorrent.antiLeechEnabled
            for (ev in events) {
                when (ev.t) {
                    1 -> torrents = bumpPeer(torrents, ev.h, +1)
                    2 -> torrents = bumpPieces(torrents, ev.h, +1)
                    3 -> Unit // hash failure — no state change surfaced
                    4 -> torrents = markComplete(torrents, ev.h)
                    5 -> torrents = markMetadata(torrents, ev.h)
                    6 -> Unit // metadata failed — surfaced via status
                    7 -> torrents = applyTrackerAnnounce(torrents, ev.h, ev.peers ?: 0)
                    8 -> dht = ev.n ?: dht
                    9 -> if (antiLeechOn) {
                        leechCount++
                        val name = ev.c ?: "未知客户端"
                        if (name !in leechClients) {
                            leechClients = (leechClients + name).takeLast(20)
                        }
                    }
                }
            }
            s.copy(dhtNodes = dht, torrents = torrents, antiLeechCount = leechCount, antiLeechClients = leechClients)
        }
    }

    /**
     * Combined per-tick refresh: torrents + global rates + DHT in a single
     * `_state.update`. The native queries are batched where the bridge
     * allows it; the whole tick emits exactly one state change so Compose
     * recomposes once per poll instead of three times.
     */
    private fun refreshStats() {
        val now = System.currentTimeMillis()
        val states = engine.torrentStates().associateBy { it.hash }
        val totals = engine.totals()
        val dt = (now - lastGlobalPoll).coerceAtLeast(1L)
        val downRate = if (lastTotals == null) 0L else (totals.first - lastTotals!!.first) * 1000 / dt
        val upRate = if (lastTotals == null) 0L else (totals.second - lastTotals!!.second) * 1000 / dt
        lastTotals = totals
        lastGlobalPoll = now
        _state.update { s ->
            val updated = records.map { rec ->
                val base = s.torrents.firstOrNull { it.hash == rec.hash }
                buildTorrent(rec, base, states[rec.hash], now)
            }
            s.copy(
                torrents = updated,
                globalDownRate = downRate.coerceAtLeast(0),
                globalUpRate = upRate.coerceAtLeast(0),
                totalDownloaded = totals.first,
                totalUploaded = totals.second,
                dhtNodes = engine.dhtNodeCount(),
            )
        }
    }

    private fun buildTorrent(rec: TorrentRecord, base: Torrent?, state: com.typebit.engine.TorrentStateDto?, now: Long): Torrent {
        val info = engine.torrentInfo(rec.hash)
        val progress = engine.progress(rec.hash)
        val downloaded = engine.downloaded(rec.hash)
        val complete = engine.isComplete(rec.hash)

        // Status resolution.
        val status = when {
            state?.paused == true || (base?.status == TorrentStatus.PAUSED && progress == base.progress && base.status != TorrentStatus.SEEDING) && !complete -> TorrentStatus.PAUSED
            !rec.paused && complete -> TorrentStatus.SEEDING
            !rec.paused && progress in 0.0001..0.9999 -> TorrentStatus.DOWNLOADING
            !rec.paused && progress < 0.0001 && info?.metadata_ready == false -> TorrentStatus.FETCHING_METADATA
            !rec.paused && progress < 0.0001 -> TorrentStatus.DOWNLOADING
            rec.paused -> TorrentStatus.PAUSED
            else -> TorrentStatus.STOPPED
        }

        // Per-torrent download rate from deltas.
        val prev = lastSeen[rec.hash]
        val dt = (now - (prev?.first ?: now)).coerceAtLeast(1L)
        val downRate = if (prev == null) 0L else (downloaded - prev.second).coerceAtLeast(0) * 1000 / dt
        lastSeen[rec.hash] = now to downloaded

        val havePieces = state?.have?.toInt() ?: base?.havePieces ?: 0
        return Torrent(
            hash = rec.hash,
            name = info?.effectiveName() ?: rec.name,
            saveDir = rec.saveDir,
            status = status,
            sizeBytes = info?.size ?: base?.sizeBytes ?: 0L,
            downloadedBytes = downloaded,
            uploadedBytes = 0L, // typebit 0.1.0 limitation — see README
            progress = progress,
            pieceCount = info?.piece_count?.toInt() ?: base?.pieceCount ?: 0,
            havePieces = havePieces,
            pieceLength = info?.piece_length ?: base?.pieceLength ?: 0L,
            isPrivate = info?.`private` ?: base?.isPrivate ?: false,
            metadataReady = info?.metadata_ready ?: base?.metadataReady ?: false,
            addedAt = rec.addedAt,
            createdAt = info?.creation_date?.times(1000),
            createdBy = info?.created_by,
            comment = info?.comment,
            kind = info?.kind ?: rec.kind,
            trackers = base?.trackers ?: info?.announce_list.orEmpty().flatten().map { TrackerInfo(url = it) },
            files = base?.files ?: info?.files.orEmpty().map { com.typebit.model.FileEntry(it.path, it.length) },
            seeds = base?.seeds ?: 0,
            peers = base?.peers ?: 0,
            downSpeed = downRate,
            upSpeed = 0L,
            completedAt = base?.completedAt,
            category = rec.category,
            tags = rec.tags,
            haveBitsHex = state?.hx ?: base?.haveBitsHex.orEmpty(),
        )
    }

    // ---- event helpers ----

    private fun bumpPeer(torrents: List<Torrent>, hash: String, delta: Int): List<Torrent> =
        torrents.map { if (it.hash == hash) it.copy(peers = (it.peers + delta).coerceAtLeast(0)) else it }

    private fun bumpPieces(torrents: List<Torrent>, hash: String, delta: Int): List<Torrent> =
        torrents.map { if (it.hash == hash) it.copy(havePieces = (it.havePieces + delta).coerceAtMost(it.pieceCount.coerceAtLeast(0))) else it }

    private fun markComplete(torrents: List<Torrent>, hash: String): List<Torrent> =
        torrents.map { if (it.hash == hash) it.copy(status = TorrentStatus.SEEDING, progress = 1.0, completedAt = System.currentTimeMillis()) else it }

    private fun markMetadata(torrents: List<Torrent>, hash: String): List<Torrent> =
        torrents.map { if (it.hash == hash) it.copy(metadataReady = true) else it }

    private fun applyTrackerAnnounce(torrents: List<Torrent>, hash: String, peers: Int): List<Torrent> =
        torrents.map { if (it.hash == hash) it.copy(peers = peers) else it }

    // ---- persistence helpers ----

    private fun reAddRecord(rec: TorrentRecord) {
        val hash = when (rec.kind) {
            "MAGNET" -> engine.addMagnet(rec.data, rec.saveDir)
            else -> {
                val bytes = B64.decode(rec.data)
                if (bytes == null) null else engine.addTorrent(bytes, rec.saveDir)
            }
        }
        if (hash == null) {
            _state.update { it.copy(lastError = "恢复失败：${rec.name}") }
        }
    }

    private fun markPaused(hash: String, paused: Boolean) {
        records = records.map { if (it.hash == hash) it.copy(paused = paused) else it }
        persistRecords()
    }

    private fun persistRecords() {
        torrentRepo.saveRecords(records)
    }

    private fun persistResume() {
        engine.saveState()?.let { torrentRepo.saveResumeState(it) }
    }

    private fun applyLimits(settings: AppSettings) {
        val speed = settings.speed
        val active = if (speed.alternativeLimitsEnabled && speed.scheduleEnabled && scheduleOpen(speed)) {
            speed.altDownloadLimitKib to speed.altUploadLimitKib
        } else {
            speed.globalDownloadLimitKib to speed.globalUploadLimitKib
        }
        engine.setGlobalLimits(active.first * 1024, active.second * 1024)
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
