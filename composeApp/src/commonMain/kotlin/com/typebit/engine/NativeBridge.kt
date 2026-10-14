package com.typebit.engine

// Must contain ONLY `expect` declarations: top-level code would emit a `NativeBridgeKt` class that
// collides with the `actual external` declarations in jvmShared. The loader lives in NativeRuntime.kt.

/** ABI exported by the loaded library, checked against [EXPECTED_BRIDGE_ABI] before the first real call. */
expect fun nativeBridgeAbi(): Int

/** Creates the engine worker; returns an opaque handle (0 on failure). */
expect fun nativeCreateEngine(configJson: String, saveDir: String): Long

expect fun nativeDestroyEngine(handle: Long)

/** Parses `.torrent` bytes to metainfo JSON only, without adding; null on error. */
expect fun nativeParseTorrent(data: ByteArray): String?

/**
 * Blocking v1 build. `optionsJson`: `files` (`{"abs","rel"}`), `piece_length` (power of two,
 * 16 KiB..256 MiB), `name`, `announce`, `announce_list`, `comment`, `source`, `private`.
 */
expect fun nativeMakeTorrent(optionsJson: String): ByteArray?

/** Progress of the in-flight [nativeMakeTorrent]: `{"done":n,"total":n,"running":b,"cancelled":b}`. */
expect fun nativeMakeTorrentProgress(): String

expect fun nativeMakeTorrentCancel(): Int

/**
 * Hashes on-disk files into the engine, which otherwise starts every session with an empty bitfield
 * (an already-complete torrent would re-download and never seed). Blocking; negative = error.
 */
expect fun nativeRecheckData(handle: Long, hash: String): Int

/** Progress of the in-flight [nativeRecheckData]: `{"done":n,"total":n,"running":b,"cancelled":b}`. */
expect fun nativeRecheckProgress(): String

expect fun nativeRecheckCancel(): Int

/** `prioritiesJson`: one byte per file in table order (0=Skip 1=Normal 2=High); empty or `[]` = all Normal. */
expect fun nativeAddTorrent(handle: Long, data: ByteArray, saveDir: String, prioritiesJson: String): String?

/** Adds a magnet URI; returns the hex infohash, or null on error. */
expect fun nativeAddMagnet(handle: Long, uri: String, saveDir: String): String?

/** Announces and connects; 0 = ok, negative = error. */
expect fun nativeStart(handle: Long, hash: String): Int

/** Atomically replaces ALL priorities and releases any two-phase magnet hold; `prioritiesJson` as in [nativeAddTorrent]. */
expect fun nativeSetFilePriorities(handle: Long, hash: String, prioritiesJson: String): Int

/** Two-phase magnet: `hold != 0` fetches metadata but requests NO pieces until [nativeSetFilePriorities]. */
expect fun nativeSetHoldData(handle: Long, hash: String, hold: Int): Int

expect fun nativePause(handle: Long, hash: String): Int

expect fun nativeResume(handle: Long, hash: String): Int

expect fun nativeRemove(handle: Long, hash: String): Int

/** `file` indexes the file table; only the final promotion and the display name use the new name. */
expect fun nativeRenameFile(handle: Long, hash: String, file: Int, name: String): Int

/** Display name only; never renames files on disk. */
expect fun nativeRenameTorrent(handle: Long, hash: String, name: String): Int

/** Progress in 0.0..1.0. */
expect fun nativeProgress(handle: Long, hash: String): Double

/** Payload bytes only, no protocol overhead. */
expect fun nativeDownloaded(handle: Long, hash: String): Long

expect fun nativeIsComplete(handle: Long, hash: String): Boolean

/** Mirrored metainfo JSON, null while a magnet has no metadata yet. */
expect fun nativeTorrentInfo(handle: Long, hash: String): String?

expect fun nativeTorrentInfoRaw(handle: Long, hash: String): String?

expect fun nativeTorrentStates(handle: Long): String

expect fun nativePeers(handle: Long, hash: String): String

/**
 * One batched UI snapshot: `{"dht":n,"torrents":[{"h","p","d","c","paused","have","hx","name","size","pieces","meta"},…]}`.
 */
expect fun nativeSnapshot(handle: Long): String

expect fun nativeTorrentCount(handle: Long): Int

expect fun nativeDhtNodeCount(handle: Long): Int

/** Our peer id: 20 bytes, hex-encoded. */
expect fun nativePeerId(handle: Long): String

/** Global wire counters: `{"d":down,"u":up}`. */
expect fun nativeTotals(handle: Long): String

expect fun nativeStats(handle: Long): String

/** Signed proof-of-download receipt JSON, or `{"error":"…"}`. */
expect fun nativeExportReceipt(
    handle: Long,
    hash: String,
    rangeStart: Long,
    rangeEnd: Long,
    epochStart: Long,
    epochEnd: Long,
): String?

/** Returns a `ReceiptVerifyResultDto` JSON. */
expect fun nativeVerifyReceipt(handle: Long, json: String): String?

/**
 * Adds TCP+UDP inbound rules for `port`, returning `{"ok":bool,"message":".."}`. Retry through
 * [nativeFirewallAddElevated] when not elevated; Android answers with a Windows-only error.
 */
expect fun nativeFirewallAdd(port: Int): String

/** Retries through a single UAC elevation prompt. */
expect fun nativeFirewallAddElevated(port: Int): String

expect fun nativeFirewallRemove(port: Int): String

expect fun nativeFirewallStatus(port: Int): String

expect fun nativeIcsStatus(): String

/** Explicit, admin-gated action. */
expect fun nativeIcsEnable(): String

expect fun nativeIcsDisable(): String

/** Limits in bytes per second; 0 = unlimited. */
expect fun nativeSetGlobalLimits(handle: Long, downBytesPerSec: Long, upBytesPerSec: Long): Int

/** Applies session defaults to torrents added from now on. */
expect fun nativeSetSessionConfig(handle: Long, configJson: String): Int

/** Priority: 0=Skip, 1=Normal, 2=High. 0 = ok, negative = error. */
expect fun nativeSetFilePriority(handle: Long, hash: String, file: Int, priority: Int): Int

expect fun nativeFilePriorities(handle: Long, hash: String): String?

expect fun nativeAddTracker(handle: Long, hash: String, url: String): Int

expect fun nativeRemoveTracker(handle: Long, hash: String, url: String): Int

expect fun nativeTrackers(handle: Long, hash: String): String?

/** Serialized resume state: torrents + DHT nodes. */
expect fun nativeSaveState(handle: Long): ByteArray?

expect fun nativeLoadState(handle: Long, data: ByteArray): Int

/** Drains (consumes) the pending queue into one JSON array. */
expect fun nativeTakeEvents(handle: Long): String

/** Drains (consumes) the pending queue into one JSON array. */
expect fun nativeTakeLogs(handle: Long): String
