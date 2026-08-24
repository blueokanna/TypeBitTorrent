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

    fun addTorrent(data: ByteArray, saveDir: String): String?

    fun addMagnet(uri: String, saveDir: String): String?

    fun start(hash: String): Boolean

    fun pause(hash: String)

    fun resume(hash: String)

    fun remove(hash: String): Boolean

    // -- queries ------------------------------------------------------------

    fun progress(hash: String): Double

    fun downloaded(hash: String): Long

    fun isComplete(hash: String): Boolean

    fun torrentInfo(hash: String): TorrentInfoDto?

    /** All torrents' persisted have/paused state. */
    fun torrentStates(): List<TorrentStateDto>

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

    override fun addTorrent(data: ByteArray, saveDir: String): String? =
        requireEngine().let { nativeAddTorrent(it, data, saveDir) }

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
