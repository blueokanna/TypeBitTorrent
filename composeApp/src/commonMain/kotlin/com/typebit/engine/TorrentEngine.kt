package com.typebit.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Shared lenient decoder for the bridge JSON contract. */
private val BRIDGE_JSON = Json { ignoreUnknownKeys = true }

/**
 * The engine facade — the single seam the rest of the app talks to.
 *
 * Kept deliberately narrow so the store never touches JNI or JSON details.
 * All methods are safe to call from any thread (the Rust worker serializes
 * commands internally); queries block briefly on a bounded one-shot reply.
 */
interface TorrentEngine {

    /** Creates the engine worker. Returns false when the native lib is absent. */
    fun start(configJson: String, saveDir: String): Boolean

    /** Shuts the worker down and frees the handle. Idempotent. */
    fun stop()

    val isRunning: Boolean

    // -- torrents -----------------------------------------------------------

    /** Parses `.torrent` bytes without adding (add-dialog preview). */
    fun parseTorrent(data: ByteArray): TorrentInfoDto?

    /**
     * Adds a `.torrent` with per-file priorities (0=Skip, 1=Normal, 2=High)
     * aligned with the file table. Empty list keeps every file at Normal.
     */
    fun addTorrent(data: ByteArray, saveDir: String, filePriorities: List<Int> = emptyList()): String?

    fun addMagnet(uri: String, saveDir: String): String?

    fun start(hash: String): Boolean

    fun pause(hash: String)

    fun resume(hash: String)

    fun remove(hash: String): Boolean

    /** Renames one file of a torrent; false when the name is invalid. */
    fun renameFile(hash: String, file: Int, name: String): Boolean

    // -- selective download + runtime trackers (typebit 0.1.1) --------------

    /** Sets one file's priority: 0=Skip, 1=Normal, 2=High. */
    fun setFilePriority(hash: String, file: Int, priority: Int): Boolean

    /** Current per-file priorities of a torrent, or null when unknown. */
    fun filePriorities(hash: String): List<Int>?

    /** Adds a tracker URL to a running torrent (no restart needed). */
    fun addTracker(hash: String, url: String): Boolean

    /** Removes a tracker URL from a running torrent. */
    fun removeTracker(hash: String, url: String): Boolean

    /** Current tracker URLs of a torrent, or null when unknown. */
    fun trackers(hash: String): List<String>?

    /** Live peer snapshot of a torrent (empty when none connected). */
    fun peers(hash: String): List<PeerDto>

    // -- queries ------------------------------------------------------------

    fun progress(hash: String): Double

    fun downloaded(hash: String): Long

    fun isComplete(hash: String): Boolean

    fun torrentInfo(hash: String): TorrentInfoDto?

    /** All torrents' persisted have/paused state. */
    fun torrentStates(): List<TorrentStateDto>

    /**
     * One batched snapshot for a whole poll tick — DHT count plus every
     * torrent's runtime stats (progress/downloaded/complete/paused/have +
     * meta essentials). One JNI round-trip instead of 4N+3.
     */
    fun snapshot(): EngineSnapshotDto

    fun torrentCount(): Int

    fun dhtNodeCount(): Int

    fun peerId(): String

    /** Cumulative wire bytes: (downloaded, uploaded). */
    fun totals(): Pair<Long, Long>

    // -- configuration ------------------------------------------------------

    fun setGlobalLimits(downBytesPerSec: Long, upBytesPerSec: Long)

    fun setSessionConfig(configJson: String)

    // -- persistence --------------------------------------------------------

    fun saveState(): ByteArray?

    fun loadState(data: ByteArray)

    // -- polling ------------------------------------------------------------

    fun takeEvents(): List<EngineEventDto>

    fun takeLogs(): List<LogEntryDto>
}

/** JNI-backed implementation. */
class NativeTorrentEngine : TorrentEngine {

    private var handle: Long = 0L

    override val isRunning: Boolean get() = handle != 0L

    override fun start(configJson: String, saveDir: String): Boolean {
        ensureNativeLoaded()
        if (handle != 0L) return true
        handle = nativeCreateEngine(configJson, saveDir)
        return handle != 0L
    }

    override fun stop() {
        if (handle != 0L) {
            nativeDestroyEngine(handle)
            handle = 0L
        }
    }

    override fun parseTorrent(data: ByteArray): TorrentInfoDto? {
        val json = nativeParseTorrent(data) ?: return null
        return runCatching { BRIDGE_JSON.decodeFromString<TorrentInfoDto>(json) }.getOrNull()
    }

    override fun addTorrent(data: ByteArray, saveDir: String, filePriorities: List<Int>): String? {
        val prioJson = filePriorities.joinToString(prefix = "[", postfix = "]")
        return requireEngine().let { nativeAddTorrent(it, data, saveDir, prioJson) }
    }

    override fun addMagnet(uri: String, saveDir: String): String? =
        requireEngine().let { nativeAddMagnet(it, uri, saveDir) }

    override fun start(hash: String): Boolean = requireEngine().let { nativeStart(it, hash) == 0 }

    override fun pause(hash: String) {
        requireEngine().let { nativePause(it, hash) }
    }

    override fun resume(hash: String) {
        requireEngine().let { nativeResume(it, hash) }
    }

    override fun remove(hash: String): Boolean = requireEngine().let { nativeRemove(it, hash) == 0 }

    override fun renameFile(hash: String, file: Int, name: String): Boolean =
        requireEngine().let { nativeRenameFile(it, hash, file, name) == 0 }

    override fun setFilePriority(hash: String, file: Int, priority: Int): Boolean =
        requireEngine().let { nativeSetFilePriority(it, hash, file, priority) == 0 }

    override fun filePriorities(hash: String): List<Int>? {
        val json = requireEngine().let { nativeFilePriorities(it, hash) } ?: return null
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<Int>>(json)
        }.getOrNull()
    }

    override fun addTracker(hash: String, url: String): Boolean =
        requireEngine().let { nativeAddTracker(it, hash, url) == 0 }

    override fun removeTracker(hash: String, url: String): Boolean =
        requireEngine().let { nativeRemoveTracker(it, hash, url) == 0 }

    override fun trackers(hash: String): List<String>? {
        val json = requireEngine().let { nativeTrackers(it, hash) } ?: return null
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<String>>(json)
        }.getOrNull()
    }

    override fun peers(hash: String): List<PeerDto> {
        val json = requireEngine().let { nativePeers(it, hash) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<PeerDto>>(json)
        }.getOrDefault(emptyList())
    }

    override fun progress(hash: String): Double = requireEngine().let { nativeProgress(it, hash) }

    override fun downloaded(hash: String): Long = requireEngine().let { nativeDownloaded(it, hash) }

    override fun isComplete(hash: String): Boolean = requireEngine().let { nativeIsComplete(it, hash) }

    override fun torrentInfo(hash: String): TorrentInfoDto? {
        val json = requireEngine().let { nativeTorrentInfo(it, hash) } ?: return null
        return runCatching { BRIDGE_JSON.decodeFromString<TorrentInfoDto>(json) }.getOrNull()
    }

    override fun torrentStates(): List<TorrentStateDto> {
        val json = requireEngine().let { nativeTorrentStates(it) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<TorrentStateDto>>(json)
        }.getOrDefault(emptyList())
    }

    override fun snapshot(): EngineSnapshotDto {
        val json = requireEngine().let { nativeSnapshot(it) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<EngineSnapshotDto>(json)
        }.getOrDefault(EngineSnapshotDto())
    }

    override fun torrentCount(): Int = requireEngine().let { nativeTorrentCount(it) }

    override fun dhtNodeCount(): Int = requireEngine().let { nativeDhtNodeCount(it) }

    override fun peerId(): String = requireEngine().let { nativePeerId(it) }

    override fun totals(): Pair<Long, Long> {
        val json = requireEngine().let { nativeTotals(it) }
        return runCatching {
            val o = BRIDGE_JSON.parseToJsonElement(json).jsonObject
            (o["d"]?.jsonPrimitive?.longOrNull ?: 0L) to (o["u"]?.jsonPrimitive?.longOrNull ?: 0L)
        }.getOrDefault(0L to 0L)
    }

    override fun setGlobalLimits(downBytesPerSec: Long, upBytesPerSec: Long) {
        requireEngine().let { nativeSetGlobalLimits(it, downBytesPerSec, upBytesPerSec) }
    }

    override fun setSessionConfig(configJson: String) {
        requireEngine().let { nativeSetSessionConfig(it, configJson) }
    }

    override fun saveState(): ByteArray? = requireEngine().let { nativeSaveState(it) }

    override fun loadState(data: ByteArray) {
        requireEngine().let { nativeLoadState(it, data) }
    }

    override fun takeEvents(): List<EngineEventDto> {
        val json = requireEngine().let { nativeTakeEvents(it) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<EngineEventDto>>(json)
        }.getOrDefault(emptyList())
    }

    override fun takeLogs(): List<LogEntryDto> {
        val json = requireEngine().let { nativeTakeLogs(it) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<LogEntryDto>>(json)
        }.getOrDefault(emptyList())
    }

    private fun requireEngine(): Long {
        check(handle != 0L) { "engine not started" }
        return handle
    }
}
