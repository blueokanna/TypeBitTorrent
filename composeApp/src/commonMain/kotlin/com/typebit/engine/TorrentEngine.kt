package com.typebit.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Shared lenient decoder for the bridge JSON contract. */
private val BRIDGE_JSON = Json { ignoreUnknownKeys = true }

/**
 * Everything the `.torrent` builder needs.
 *
 * @param files `(absolute path, relative path components)` — the rel path is
 *   what lands in the torrent, so a picked folder keeps its structure.
 * @param pieceLength power of two in 16 KiB .. 256 MiB.
 * @param announce tier-0 tracker list; each URL is validated by the builder
 *   (a bad URL is dropped rather than aborting the build).
 * @param announceList BEP-12 tiers, used instead of [announce] when present.
 * @param comment free-form comment (kept verbatim, trimmed).
 * @param source cross-seed tag written into `info`.
 * @param isPrivate BEP-27 `private: 1`.
 */
data class MakeTorrentOptions(
    val files: List<Pair<String, List<String>>>,
    val pieceLength: Int,
    val name: String,
    val announce: List<String> = emptyList(),
    val announceList: List<List<String>> = emptyList(),
    val comment: String? = null,
    val source: String? = null,
    val isPrivate: Boolean = false,
)

/** Snapshot of a running (or finished) `.torrent` build. */
data class MakeTorrentProgress(
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val running: Boolean = false,
    val cancelled: Boolean = false,
) {
    /** 0f..1f; 0 while the total size is still unknown. */
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

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
    ): ByteArray? =
        makeTorrent(
            MakeTorrentOptions(
                files = files,
                pieceLength = pieceLength,
                name = name,
                announce = listOfNotNull(announce?.takeIf { it.isNotBlank() }),
                comment = comment,
            )
        )

    /**
     * Full-featured v1 creation: announce tiers (BEP-12), `private` (BEP-27),
     * cross-seed `source` tag and the created-by/comment fields.
     */
    fun makeTorrent(options: MakeTorrentOptions): ByteArray?

    /** Live progress of the in-flight [makeTorrent] (idle when nothing runs). */
    fun makeTorrentProgress(): MakeTorrentProgress = MakeTorrentProgress()

    /** Requests cancellation of the in-flight [makeTorrent]. */
    fun cancelMakeTorrent(): Boolean = false

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

/**
 * JNI-backed implementation.
 *
 * Failure contract — the reason this class exists in this shape: a JNI call
 * can fail in ways that are NOT exceptions from our own code (`UnsatisfiedLinkError`
 * when the `.so`/.dll is missing or built for another ABI, a stale/zero handle
 * after teardown, an `IllegalStateException` from the bridge). The UI calls
 * these methods from composition-scoped coroutines (the Peers tab polls every
 * 2 s, the Files tab applies priorities on tap); on Android an uncaught
 * exception in such a coroutine **kills the process instantly** — the "点击
 * Peers/文件 闪退" crash. Every native entry point below therefore degrades to
 * a safe default instead of throwing, and the failure is recorded in
 * [lastBridgeFailure] + stdout (logcat) so it is still diagnosable.
 */
class NativeTorrentEngine : TorrentEngine {

    /** Opaque engine pointer; `0` = no engine. Volatile: read by the poll
     *  loop / peers view / UI threads while `stop()` zeroes it elsewhere. */
    @Volatile
    private var handle: Long = 0L

    /** Most recent non-fatal bridge failure (`null` when healthy). */
    @Volatile
    var lastBridgeFailure: String? = null
        private set

    override val isRunning: Boolean get() = handle != 0L

    override fun start(configJson: String, saveDir: String): Boolean {
        if (handle != 0L) return true
        // `ensureNativeLoaded()` throws IllegalStateException and the JNI
        // lookup can throw UnsatisfiedLinkError — an `Error`, which a plain
        // `catch (e: Exception)` would miss. Both must become `false`, never
        // propagate: the store boots this from a background job.
        val created = try {
            ensureNativeLoaded()
            // Refuse to call into a library whose JNI surface drifted: a
            // signature mismatch is not a catchable error, it is a native
            // crash (wrong register/slot layout), so the check must happen
            // before the first real call.
            val abi = runCatching { nativeBridgeAbi() }.getOrDefault(-1)
            if (abi != EXPECTED_BRIDGE_ABI) {
                fail(
                    "start",
                    IllegalStateException(
                        "原生库 ABI 不匹配（库=$abi，程序需要=$EXPECTED_BRIDGE_ABI）；" +
                            "请重新安装完整包或运行 scripts/build-android.ps1 / build-desktop.ps1 重新构建原生库"
                    ),
                )
                return false
            }
            nativeCreateEngine(configJson, saveDir)
        } catch (t: Throwable) {
            fail("start", t)
            0L
        }
        if (created == 0L) return false
        handle = created
        return true
    }

    override fun stop() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            try {
                nativeDestroyEngine(h)
            } catch (t: Throwable) {
                fail("stop", t)
            }
        }
    }

    override fun parseTorrent(data: ByteArray): TorrentInfoDto? {
        val json = try {
            nativeParseTorrent(data)
        } catch (t: Throwable) {
            fail("parseTorrent", t)
            null
        } ?: return null
        return runCatching { BRIDGE_JSON.decodeFromString<TorrentInfoDto>(json) }.getOrNull()
    }

    /** Records a swallowed bridge failure (logcat on Android, stderr elsewhere). */
    private fun fail(where: String, t: Throwable) {
        val msg = "$where: ${t::class.simpleName}: ${t.message}"
        lastBridgeFailure = msg
        println("typebit_native bridge failure — $msg")
    }

    /**
     * Runs one native call against the live handle.
     *
     * A `0` handle is passed through on purpose: the Rust side maps it to
     * "no engine" and answers with the contract default, so the common
     * shutdown race (UI query in flight while the store tears the engine
     * down) can never raise on the Kotlin side.
     */
    private inline fun <T> bridge(fallback: T, call: (Long) -> T): T =
        try {
            call(handle)
        } catch (t: Throwable) {
            fail("call", t)
            fallback
        }

    override fun makeTorrent(options: MakeTorrentOptions): ByteArray? =
        bridge(null) { nativeMakeTorrent(MakeTorrentJson.options(options)) }

    override fun makeTorrentProgress(): MakeTorrentProgress {
        val json = bridge("{}") { nativeMakeTorrentProgress() }
        return runCatching {
            val o = BRIDGE_JSON.parseToJsonElement(json).jsonObject
            MakeTorrentProgress(
                doneBytes = o["done"]?.jsonPrimitive?.longOrNull ?: 0L,
                totalBytes = o["total"]?.jsonPrimitive?.longOrNull ?: 0L,
                running = o["running"]?.jsonPrimitive?.booleanOrNull ?: false,
                cancelled = o["cancelled"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.getOrDefault(MakeTorrentProgress())
    }

    override fun cancelMakeTorrent(): Boolean = bridge(false) { nativeMakeTorrentCancel() == 1 }

    override fun addTorrent(data: ByteArray, saveDir: String, filePriorities: List<Int>): String? {
        val prioJson = filePriorities.joinToString(prefix = "[", postfix = "]")
        return bridge(null) { nativeAddTorrent(it, data, saveDir, prioJson) }
    }

    override fun addMagnet(uri: String, saveDir: String): String? =
        bridge(null) { nativeAddMagnet(it, uri, saveDir) }

    override fun start(hash: String): Boolean = bridge(false) { nativeStart(it, hash) == 0 }

    override fun pause(hash: String) {
        bridge(Unit) { nativePause(it, hash) }
    }

    override fun resume(hash: String) {
        bridge(Unit) { nativeResume(it, hash) }
    }

    override fun remove(hash: String): Boolean = bridge(false) { nativeRemove(it, hash) == 0 }

    override fun renameFile(hash: String, file: Int, name: String): Boolean =
        bridge(false) { nativeRenameFile(it, hash, file, name) == 0 }

    override fun renameTorrent(hash: String, name: String): Boolean =
        bridge(false) { nativeRenameTorrent(it, hash, name) == 0 }

    override fun setFilePriority(hash: String, file: Int, priority: Int): Boolean =
        bridge(false) { nativeSetFilePriority(it, hash, file, priority) == 0 }

    override fun setFilePriorities(hash: String, priorities: List<Int>): Boolean {
        val prioJson = priorities.joinToString(prefix = "[", postfix = "]")
        return bridge(false) { nativeSetFilePriorities(it, hash, prioJson) == 0 }
    }

    override fun setHoldData(hash: String, hold: Boolean): Boolean =
        bridge(false) { nativeSetHoldData(it, hash, if (hold) 1 else 0) == 0 }

    override fun filePriorities(hash: String): List<Int>? {
        val json = bridge(null) { nativeFilePriorities(it, hash) } ?: return null
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<Int>>(json)
        }.getOrNull()
    }

    override fun addTracker(hash: String, url: String): Boolean =
        bridge(false) { nativeAddTracker(it, hash, url) == 0 }

    override fun removeTracker(hash: String, url: String): Boolean =
        bridge(false) { nativeRemoveTracker(it, hash, url) == 0 }

    override fun trackers(hash: String): List<String>? {
        val json = bridge(null) { nativeTrackers(it, hash) } ?: return null
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<String>>(json)
        }.getOrNull()
    }

    override fun peers(hash: String): List<PeerDto> {
        val json = bridge("[]") { nativePeers(it, hash) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<PeerDto>>(json)
        }.getOrDefault(emptyList())
    }

    override fun progress(hash: String): Double = bridge(0.0) { nativeProgress(it, hash) }

    override fun downloaded(hash: String): Long = bridge(0L) { nativeDownloaded(it, hash) }

    override fun isComplete(hash: String): Boolean = bridge(false) { nativeIsComplete(it, hash) }

    override fun torrentInfo(hash: String): TorrentInfoDto? {
        val json = bridge(null) { nativeTorrentInfo(it, hash) } ?: return null
        return runCatching { BRIDGE_JSON.decodeFromString<TorrentInfoDto>(json) }.getOrNull()
    }

    override fun torrentInfoRaw(hash: String): String? =
        bridge(null) { nativeTorrentInfoRaw(it, hash) }

    override fun torrentStates(): List<TorrentStateDto> {
        val json = bridge("[]") { nativeTorrentStates(it) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<TorrentStateDto>>(json)
        }.getOrDefault(emptyList())
    }

    override fun snapshot(): EngineSnapshotDto {
        val json = bridge("{}") { nativeSnapshot(it) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<EngineSnapshotDto>(json)
        }.getOrDefault(EngineSnapshotDto())
    }

    override fun torrentCount(): Int = bridge(0) { nativeTorrentCount(it) }

    override fun dhtNodeCount(): Int = bridge(0) { nativeDhtNodeCount(it) }

    override fun peerId(): String = bridge("") { nativePeerId(it) }

    override fun totals(): Pair<Long, Long> {
        val json = bridge("{}") { nativeTotals(it) }
        return runCatching {
            val o = BRIDGE_JSON.parseToJsonElement(json).jsonObject
            (o["d"]?.jsonPrimitive?.longOrNull ?: 0L) to (o["u"]?.jsonPrimitive?.longOrNull ?: 0L)
        }.getOrDefault(0L to 0L)
    }

    override fun stats(): EngineStatsDto {
        val json = bridge("{}") { nativeStats(it) }
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
    ): String? = bridge(null) {
        nativeExportReceipt(it, hash, rangeStart, rangeEnd, epochStart, epochEnd)
    }

    override fun verifyReceipt(json: String): ReceiptVerifyResultDto {
        val out = bridge(null) { nativeVerifyReceipt(it, json) }
            ?: return ReceiptVerifyResultDto(ok = false, error = "engine not running")
        return runCatching {
            BRIDGE_JSON.decodeFromString<ReceiptVerifyResultDto>(out)
        }.getOrElse { ReceiptVerifyResultDto(ok = false, error = "invalid response") }
    }

    override fun setGlobalLimits(downBytesPerSec: Long, upBytesPerSec: Long) {
        bridge(Unit) { nativeSetGlobalLimits(it, downBytesPerSec, upBytesPerSec) }
    }

    override fun setSessionConfig(configJson: String) {
        bridge(Unit) { nativeSetSessionConfig(it, configJson) }
    }

    /**
     * Windows system helpers (firewall / ICS) are engine-independent, but
     * they still live in the same cdylib: on a platform where the symbol is
     * absent the call raises `UnsatisfiedLinkError`, which must surface as a
     * plain "unsupported" result — never as a crash in the settings screen.
     */
    private inline fun system(call: () -> String): SystemResultDto =
        try {
            decodeSystemResult(call())
        } catch (t: Throwable) {
            fail("system", t)
            SystemResultDto(ok = false, message = t.message ?: "native call failed")
        }

    override fun firewallAdd(port: Int): SystemResultDto = system { nativeFirewallAdd(port) }

    override fun firewallAddElevated(port: Int): SystemResultDto =
        system { nativeFirewallAddElevated(port) }

    override fun firewallRemove(port: Int): SystemResultDto = system { nativeFirewallRemove(port) }

    override fun firewallStatus(port: Int): SystemResultDto = system { nativeFirewallStatus(port) }

    override fun icsStatus(): SystemResultDto = system { nativeIcsStatus() }

    override fun icsEnable(): SystemResultDto = system { nativeIcsEnable() }

    override fun icsDisable(): SystemResultDto = system { nativeIcsDisable() }

    override fun saveState(): ByteArray? = bridge(null) { nativeSaveState(it) }

    override fun loadState(data: ByteArray) {
        bridge(Unit) { nativeLoadState(it, data) }
    }

    override fun takeEvents(): List<EngineEventDto> {
        val json = bridge("[]") { nativeTakeEvents(it) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<EngineEventDto>>(json)
        }.getOrDefault(emptyList())
    }

    override fun takeLogs(): List<LogEntryDto> {
        val json = bridge("[]") { nativeTakeLogs(it) }
        return runCatching {
            BRIDGE_JSON.decodeFromString<List<LogEntryDto>>(json)
        }.getOrDefault(emptyList())
    }
}

/**
 * Encoder for the native `nativeMakeTorrent` options object.
 *
 * Hand-rolled for the same reason the native side hand-rolls its writer:
 * the shape is small, fixed and must stay byte-exact with the Rust decoder
 * (`parse_make_options`). Path components are escaped through [jsonString],
 * which is the only place user input reaches the native parser.
 */
private object MakeTorrentJson {
    fun options(o: MakeTorrentOptions): String = buildString {
        append('{')
        append("\"files\":[")
        o.files.forEachIndexed { i, (abs, rel) ->
            if (i > 0) append(',')
            append("{\"abs\":").append(jsonString(abs)).append(",\"rel\":[")
            rel.forEachIndexed { j, c ->
                if (j > 0) append(',')
                append(jsonString(c))
            }
            append("]}")
        }
        append("],\"piece_length\":").append(o.pieceLength)
        append(",\"name\":").append(jsonString(o.name))
        if (o.announceList.isNotEmpty()) {
            append(",\"announce_list\":[")
            o.announceList.forEachIndexed { i, tier ->
                if (i > 0) append(',')
                append('[')
                tier.forEachIndexed { j, url ->
                    if (j > 0) append(',')
                    append(jsonString(url))
                }
                append(']')
            }
            append(']')
        } else if (o.announce.isNotEmpty()) {
            append(",\"announce\":[")
            o.announce.forEachIndexed { i, url ->
                if (i > 0) append(',')
                append(jsonString(url))
            }
            append(']')
        }
        o.comment?.let { append(",\"comment\":").append(jsonString(it)) }
        o.source?.let { append(",\"source\":").append(jsonString(it)) }
        if (o.isPrivate) append(",\"private\":true")
        append('}')
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
