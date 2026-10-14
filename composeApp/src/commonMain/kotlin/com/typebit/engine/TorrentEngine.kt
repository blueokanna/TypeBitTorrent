package com.typebit.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Shared lenient decoder for the bridge JSON contract. */
private val BRIDGE_JSON = Json { ignoreUnknownKeys = true }

/**
 * `rel` components are what lands in the torrent (folders keep their structure) and invalid announce
 * URLs are dropped, not fatal. `pieceLength` is a power of two, 16 KiB..256 MiB; `announceList`
 * (BEP-12) wins over `announce`; `isPrivate` is BEP-27.
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

data class MakeTorrentProgress(
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val running: Boolean = false,
    val cancelled: Boolean = false,
) {
    /** 0f..1f; stays 0 until the total size is known. */
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

data class RecheckProgress(
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
 * The single seam the app talks to, so the store never touches JNI or JSON. Safe from any thread;
 * queries block briefly on a bounded one-shot reply.
 */
interface TorrentEngine {

    /** False when the native library is absent. */
    fun start(configJson: String, saveDir: String): Boolean

    fun stop()

    val isRunning: Boolean

    fun parseTorrent(data: ByteArray): TorrentInfoDto?

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

    fun makeTorrent(options: MakeTorrentOptions): ByteArray?

    fun makeTorrentProgress(): MakeTorrentProgress = MakeTorrentProgress()

    fun cancelMakeTorrent(): Boolean = false

    /**
     * Verifies on-disk data and hands the verified set to the engine, which otherwise starts every
     * session with an empty bitfield. Blocking; returns the verified piece count, negative on error.
     */
    fun recheckData(hash: String): Int = -1

    fun recheckProgress(): RecheckProgress = RecheckProgress()

    fun cancelRecheck(): Boolean = false

    /** `filePriorities`: one byte per file in table order (0=Skip, 1=Normal, 2=High); empty = all Normal. */
    fun addTorrent(data: ByteArray, saveDir: String, filePriorities: List<Int> = emptyList()): String?

    fun addMagnet(uri: String, saveDir: String): String?

    fun start(hash: String): Boolean

    fun pause(hash: String)

    fun resume(hash: String)

    fun remove(hash: String): Boolean

    /** False when the name is invalid. */
    fun renameFile(hash: String, file: Int, name: String): Boolean

    fun renameTorrent(hash: String, name: String): Boolean

    fun setFilePriority(hash: String, file: Int, priority: Int): Boolean

    /** Replaces ALL priorities atomically and releases any two-phase magnet hold. */
    fun setFilePriorities(hash: String, priorities: List<Int>): Boolean

    /** Two-phase magnet: `hold` fetches metadata but requests NO pieces until [setFilePriorities] commits. */
    fun setHoldData(hash: String, hold: Boolean): Boolean

    fun filePriorities(hash: String): List<Int>?

    fun addTracker(hash: String, url: String): Boolean

    fun removeTracker(hash: String, url: String): Boolean

    fun trackers(hash: String): List<String>?

    fun peers(hash: String): List<PeerDto>

    fun progress(hash: String): Double

    fun downloaded(hash: String): Long

    fun isComplete(hash: String): Boolean

    fun torrentInfo(hash: String): TorrentInfoDto?

    /** Raw bencoded `info` dict, base64-encoded, for metadata persistence. */
    fun torrentInfoRaw(hash: String): String?

    fun torrentStates(): List<TorrentStateDto>

    /** Batched poll tick: DHT count plus each torrent's runtime stats in one JNI round-trip. */
    fun snapshot(): EngineSnapshotDto

    fun torrentCount(): Int

    fun dhtNodeCount(): Int

    fun peerId(): String

    /** Cumulative wire bytes: (downloaded, uploaded). */
    fun totals(): Pair<Long, Long>

    fun stats(): EngineStatsDto

    /**
     * Signed receipt for a byte range (inclusive start / exclusive end) over a unix wall-clock
     * window. Returns receipt JSON, or `{"error":"…"}` when verified coverage is below 90%.
     */
    fun exportReceipt(
        hash: String,
        rangeStart: Long,
        rangeEnd: Long,
        epochStart: Long,
        epochEnd: Long,
    ): String?

    fun verifyReceipt(json: String): ReceiptVerifyResultDto

    fun setGlobalLimits(downBytesPerSec: Long, upBytesPerSec: Long)

    fun setSessionConfig(configJson: String)

    // Engine-independent: shells out to `netsh` / `powershell` on the caller's IO thread.
    // Android reports a Windows-only error for every call.

    fun firewallAdd(port: Int): SystemResultDto

    /** Retries [firewallAdd] through a single UAC elevation prompt. */
    fun firewallAddElevated(port: Int): SystemResultDto

    fun firewallRemove(port: Int): SystemResultDto

    fun firewallStatus(port: Int): SystemResultDto

    fun icsStatus(): SystemResultDto

    /** Explicit, admin-gated action. */
    fun icsEnable(): SystemResultDto

    fun icsDisable(): SystemResultDto

    fun saveState(): ByteArray?

    fun loadState(data: ByteArray)

    fun takeEvents(): List<EngineEventDto>

    fun takeLogs(): List<LogEntryDto>
}

/**
 * Failure contract: a JNI call can fail outside our code (`UnsatisfiedLinkError` for a missing or
 * wrong-ABI library, a stale handle, a bridge exception). Callers run in composition-scoped
 * coroutines whose uncaught exceptions kill the Android process, so every entry point degrades to a
 * safe default and records the failure in [lastBridgeFailure] plus stdout.
 */
class NativeTorrentEngine : TorrentEngine {

    /** Opaque engine pointer; 0 = no engine. Volatile: `stop()` zeroes it while others read. */
    @Volatile
    private var handle: Long = 0L

    /** Most recent non-fatal bridge failure; null when healthy. */
    @Volatile
    var lastBridgeFailure: String? = null
        private set

    override val isRunning: Boolean get() = handle != 0L

    override fun start(configJson: String, saveDir: String): Boolean {
        if (handle != 0L) return true
        // The JNI lookup throws `UnsatisfiedLinkError` (an `Error`, not an `Exception`) and the
        // store boots this from a background job: both failures must return false, never throw.
        val created = try {
            ensureNativeLoaded()
            // Check before the first real call: a drifted JNI surface is a native crash (wrong
            // register/slot layout), not a catchable error.
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

    /** Records a swallowed bridge failure for logcat / stderr. */
    private fun fail(where: String, t: Throwable) {
        val msg = "$where: ${t::class.simpleName}: ${t.message}"
        lastBridgeFailure = msg
        println("typebit_native bridge failure — $msg")
    }

    /**
     * Runs one native call against the live handle. A `0` handle is passed through on purpose — the
     * Rust side maps it to "no engine" — so a shutdown race cannot raise here.
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

    override fun recheckData(hash: String): Int = bridge(-1) { nativeRecheckData(it, hash) }

    override fun recheckProgress(): RecheckProgress {
        val json = bridge("{}") { nativeRecheckProgress() }
        return runCatching {
            val o = BRIDGE_JSON.parseToJsonElement(json).jsonObject
            RecheckProgress(
                doneBytes = o["done"]?.jsonPrimitive?.longOrNull ?: 0L,
                totalBytes = o["total"]?.jsonPrimitive?.longOrNull ?: 0L,
                running = o["running"]?.jsonPrimitive?.booleanOrNull ?: false,
                cancelled = o["cancelled"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.getOrDefault(RecheckProgress())
    }

    override fun cancelRecheck(): Boolean = bridge(false) { nativeRecheckCancel() == 1 }

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

    /** `UnsatisfiedLinkError` where the symbol is absent must surface as an "unsupported" result, never a crash. */
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

/** Hand-rolled to stay byte-exact with the Rust decoder (`parse_make_options`); [jsonString] escapes all user input. */
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
