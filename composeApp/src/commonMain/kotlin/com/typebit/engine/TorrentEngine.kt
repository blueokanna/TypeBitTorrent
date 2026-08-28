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
     * Creates a v1 `.torrent` from local files. Each entry is
     * `(absolute path, relative path components)`; `pieceLength` must be a
     * supported power of two (16 KiB .. 256 MiB). Returns the raw bytes.
     */
    fun makeTorrent(
        files: List<Pair<String, List<String>>>,
        pieceLength: Int,
        name: String,
        announce: String?,
        comment: String?,
    ): ByteArray?

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

    /** Renames the torrent itself (display name); false when the name is invalid. */
    fun renameTorrent(hash: String, name: String): Boolean

    // -- selective download + runtime trackers (typebit 0.1.1) --------------

    /** Sets one file's priority: 0=Skip, 1=Normal, 2=High. */
    fun setFilePriority(hash: String, file: Int, priority: Int): Boolean

    /**
     * Atomically replaces ALL per-file priorities and releases any two-phase
     * magnet hold. 0=Skip, 1=Normal, 2=High, aligned with the file table.
     */
    fun setFilePriorities(hash: String, priorities: List<Int>): Boolean

    /**
     * Two-phase magnet support: `hold` makes the torrent fetch metadata / run
     * discovery but request NO data pieces until priorities are committed.
     */
    fun setHoldData(hash: String, hold: Boolean): Boolean

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

    /** Raw bencoded `info` dict (base64) for metadata persistence; null when unknown. */
    fun torrentInfoRaw(hash: String): String?

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

    /** Engine-wide statistics (wire totals, cache, peers, discarded). */
    fun stats(): EngineStatsDto

    // -- proof-of-download receipts ------------------------------------------

    /**
     * Exports a signed proof-of-download receipt for `hash` over an absolute
     * byte range (inclusive start / exclusive end), attested to a wall-clock
     * window (unix seconds). Returns the receipt JSON on success, or a
     * `{"error":"…"}` JSON when the torrent has no verified coverage of the
     * range (receipts require ≥90%). `null` only when the engine is gone.
     */
    fun exportReceipt(
        hash: String,
        rangeStart: Long,
        rangeEnd: Long,
        epochStart: Long,
        epochEnd: Long,
    ): String?

    /** Verifies a receipt JSON (Ed25519 signature + structural integrity). */
    fun verifyReceipt(json: String): ReceiptVerifyResultDto

    // -- configuration ------------------------------------------------------

    fun setGlobalLimits(downBytesPerSec: Long, upBytesPerSec: Long)

    fun setSessionConfig(configJson: String)

    // -- Windows system integration (firewall / ICS) -------------------------
    // These never need the engine handle; they shell out to `netsh` /
    // `powershell` on the caller's (IO) thread and return a truthful result.
    // Android reports "仅 Windows 支持" for every call.

    /** Adds inbound Windows firewall rules for `port` (TCP+UDP). */
    fun firewallAdd(port: Int): SystemResultDto

    /** Retries [firewallAdd] through a single UAC elevation prompt. */
    fun firewallAddElevated(port: Int): SystemResultDto

    /** Removes the inbound firewall rules for `port`. */
    fun firewallRemove(port: Int): SystemResultDto

    /** Whether the firewall rules for `port` currently exist. */
    fun firewallStatus(port: Int): SystemResultDto

    /** Query whether Internet Connection Sharing is enabled. */
    fun icsStatus(): SystemResultDto

    /** Enables Internet Connection Sharing (explicit, admin-gated). */
    fun icsEnable(): SystemResultDto

    /** Disables Internet Connection Sharing on all shared connections. */
    fun icsDisable(): SystemResultDto

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

    override fun makeTorrent(
        files: List<Pair<String, List<String>>>,
        pieceLength: Int,
        name: String,
        announce: String?,
        comment: String?,
    ): ByteArray? {
        val filesJson = buildString {
            append('[')
            files.forEachIndexed { i, (abs, rel) ->
                if (i > 0) append(',')
                append("{\"abs\":${jsonString(abs)},\"rel\":[")
                rel.forEachIndexed { j, c ->
                    if (j > 0) append(',')
                    append(jsonString(c))
                }
                append("]}")
            }
            append(']')
        }
        return nativeMakeTorrent(
            filesJson,
            pieceLength,
            name,
            announce.orEmpty(),
            comment.orEmpty(),
        )
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

    override fun renameTorrent(hash: String, name: String): Boolean =
        requireEngine().let { nativeRenameTorrent(it, hash, name) == 0 }

    override fun setFilePriority(hash: String, file: Int, priority: Int): Boolean =
        requireEngine().let { nativeSetFilePriority(it, hash, file, priority) == 0 }

    override fun setFilePriorities(hash: String, priorities: List<Int>): Boolean {
        val prioJson = priorities.joinToString(prefix = "[", postfix = "]")
        return requireEngine().let { nativeSetFilePriorities(it, hash, prioJson) == 0 }
    }

    override fun setHoldData(hash: String, hold: Boolean): Boolean =
        requireEngine().let { nativeSetHoldData(it, hash, if (hold) 1 else 0) == 0 }

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

    override fun torrentInfoRaw(hash: String): String? =
        requireEngine().let { nativeTorrentInfoRaw(it, hash) }

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

    override fun stats(): EngineStatsDto {
        val json = requireEngine().let { nativeStats(it) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<EngineStatsDto>(json)
        }.getOrDefault(EngineStatsDto())
    }

    override fun exportReceipt(
        hash: String,
        rangeStart: Long,
        rangeEnd: Long,
        epochStart: Long,
        epochEnd: Long,
    ): String? = requireEngine().let {
        nativeExportReceipt(it, hash, rangeStart, rangeEnd, epochStart, epochEnd)
    }

    override fun verifyReceipt(json: String): ReceiptVerifyResultDto {
        val out = requireEngine().let { nativeVerifyReceipt(it, json) }
            ?: return ReceiptVerifyResultDto(ok = false, error = "engine not running")
        return runCatching {
            BRIDGE_JSON.decodeFromString<ReceiptVerifyResultDto>(out)
        }.getOrElse { ReceiptVerifyResultDto(ok = false, error = "invalid response") }
    }

    override fun setGlobalLimits(downBytesPerSec: Long, upBytesPerSec: Long) {
        requireEngine().let { nativeSetGlobalLimits(it, downBytesPerSec, upBytesPerSec) }
    }

    override fun setSessionConfig(configJson: String) {
        requireEngine().let { nativeSetSessionConfig(it, configJson) }
    }

    override fun firewallAdd(port: Int): SystemResultDto =
        decodeSystemResult(nativeFirewallAdd(port))

    override fun firewallAddElevated(port: Int): SystemResultDto =
        decodeSystemResult(nativeFirewallAddElevated(port))

    override fun firewallRemove(port: Int): SystemResultDto =
        decodeSystemResult(nativeFirewallRemove(port))

    override fun firewallStatus(port: Int): SystemResultDto =
        decodeSystemResult(nativeFirewallStatus(port))

    override fun icsStatus(): SystemResultDto = decodeSystemResult(nativeIcsStatus())

    override fun icsEnable(): SystemResultDto = decodeSystemResult(nativeIcsEnable())

    override fun icsDisable(): SystemResultDto = decodeSystemResult(nativeIcsDisable())

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

/** Decodes a `{"ok":bool,"message":".."}` bridge result (lenient). */
private fun decodeSystemResult(raw: String): SystemResultDto =
    runCatching { BRIDGE_JSON.decodeFromString<SystemResultDto>(raw) }
        .getOrElse { SystemResultDto(ok = false, message = raw) }

/** JSON string literal with proper escaping (for the make-torrent file list). */
private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
