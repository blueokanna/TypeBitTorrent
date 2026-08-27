package com.typebit.engine

// NOTE: this file intentionally contains ONLY `expect` declarations.
// Any top-level executable code here would emit a `NativeBridgeKt` JVM class
// and collide with the `actual external` declarations in jvmShared (same
// file name → same class name). The loader lives in NativeRuntime.kt.

// ---------------------------------------------------------------------------
// Engine lifecycle
// ---------------------------------------------------------------------------

/** Creates the engine worker; returns an opaque handle (0 on failure). */
expect fun nativeCreateEngine(configJson: String, saveDir: String): Long

/** Tears down the engine worker and frees the handle. */
expect fun nativeDestroyEngine(handle: Long)

// ---------------------------------------------------------------------------
// Torrents
// ---------------------------------------------------------------------------

/** Parses `.torrent` bytes → metainfo JSON (no engine add); null on error. */
expect fun nativeParseTorrent(data: ByteArray): String?

/**
 * Creates a v1 `.torrent` from local files (blocking — call off the main
 * thread). `filesJson` is `[{"abs":"C:/x/a.bin","rel":["dir","a.bin"]},…]`;
 * `pieceLength` must be a supported power of two (16 KiB .. 256 MiB).
 * Returns the raw `.torrent` bytes, or null on error.
 */
expect fun nativeMakeTorrent(filesJson: String, pieceLength: Int, name: String, announce: String, comment: String): ByteArray?

/**
 * Adds a `.torrent`; returns the hex infohash or null on error.
 * `prioritiesJson` is a JSON array of per-file priority bytes
 * (`[0,1,2,…]`; 0=Skip 1=Normal 2=High) aligned with the file table.
 * Empty/`[]` keeps every file at Normal.
 */
expect fun nativeAddTorrent(handle: Long, data: ByteArray, saveDir: String, prioritiesJson: String): String?

/** Adds a magnet URI; returns the hex infohash or null on error. */
expect fun nativeAddMagnet(handle: Long, uri: String, saveDir: String): String?

/** Starts (announces + connects) a torrent. 0 = ok, negative = error. */
expect fun nativeStart(handle: Long, hash: String): Int

/**
 * Atomically replaces ALL per-file priorities and releases any two-phase
 * magnet hold. `prioritiesJson` is `[0,1,2,…]` (0=Skip 1=Normal 2=High)
 * aligned with the file table. 0 = ok, negative = error.
 */
expect fun nativeSetFilePriorities(handle: Long, hash: String, prioritiesJson: String): Int

/**
 * Two-phase magnet support: `hold != 0` makes the torrent fetch metadata /
 * run discovery but request NO data pieces until priorities are committed
 * via [nativeSetFilePriorities]. 0 = ok, negative = error.
 */
expect fun nativeSetHoldData(handle: Long, hash: String, hold: Int): Int

/** Pauses a torrent. */
expect fun nativePause(handle: Long, hash: String): Int

/** Resumes a paused torrent. */
expect fun nativeResume(handle: Long, hash: String): Int

/** Removes a torrent. 0 = ok, negative = error. */
expect fun nativeRemove(handle: Long, hash: String): Int

/**
 * Renames one file of a torrent (index into its file table). The engine
 * keeps writing to the original staged path; the rename affects the final
 * promotion and the UI display. 0 = ok, negative = invalid name/error.
 */
expect fun nativeRenameFile(handle: Long, hash: String, file: Int, name: String): Int

/**
 * Renames the torrent itself (display name only). 0 = ok, negative = error.
 */
expect fun nativeRenameTorrent(handle: Long, hash: String, name: String): Int

// ---------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------

/** Progress in 0.0..1.0. */
expect fun nativeProgress(handle: Long, hash: String): Double

/** Bytes downloaded (payload) for a torrent. */
expect fun nativeDownloaded(handle: Long, hash: String): Long

/** True once all pieces are verified. */
expect fun nativeIsComplete(handle: Long, hash: String): Boolean

/** Mirrored metainfo JSON for a torrent, or null when unknown (magnet). */
expect fun nativeTorrentInfo(handle: Long, hash: String): String?

expect fun nativeTorrentInfoRaw(handle: Long, hash: String): String?

/** All torrents' persisted state as a JSON array. */
expect fun nativeTorrentStates(handle: Long): String

/** Live peer snapshot of a torrent as a JSON array. */
expect fun nativePeers(handle: Long, hash: String): String

/**
 * One batched UI snapshot: `{"dht":n,"torrents":[{"h","p","d","c","paused",
 * "have","hx","name","size","pieces","meta"},…]}`. Replaces the per-torrent
 * query fan-out (progress/downloaded/isComplete/torrentInfo) with one call.
 */
expect fun nativeSnapshot(handle: Long): String

/** Number of torrents in the engine. */
expect fun nativeTorrentCount(handle: Long): Int

/** Current DHT routing-table size. */
expect fun nativeDhtNodeCount(handle: Long): Int

/** Our 20-byte peer id, hex-encoded. */
expect fun nativePeerId(handle: Long): String

/** Global wire counters JSON: `{"d":down,"u":up}`. */
expect fun nativeTotals(handle: Long): String

expect fun nativeStats(handle: Long): String

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/** Sets global download/upload limits in bytes per second (0 = unlimited). */
expect fun nativeSetGlobalLimits(handle: Long, downBytesPerSec: Long, upBytesPerSec: Long): Int

/** Applies session defaults for torrents added from now on. */
expect fun nativeSetSessionConfig(handle: Long, configJson: String): Int

// ---------------------------------------------------------------------------
// Selective download + runtime trackers (typebit 0.1.1)
// ---------------------------------------------------------------------------

/** Sets one file's priority: 0=Skip, 1=Normal, 2=High. 0 = ok, negative = err. */
expect fun nativeSetFilePriority(handle: Long, hash: String, file: Int, priority: Int): Int

/** Current per-file priorities of a torrent as a JSON array (or null). */
expect fun nativeFilePriorities(handle: Long, hash: String): String?

/** Adds a tracker URL to a running torrent. 0 = ok, negative = err. */
expect fun nativeAddTracker(handle: Long, hash: String, url: String): Int

/** Removes a tracker URL from a running torrent. 0 = ok, negative = err. */
expect fun nativeRemoveTracker(handle: Long, hash: String, url: String): Int

/** Current tracker URLs of a torrent as a JSON array (or null). */
expect fun nativeTrackers(handle: Long, hash: String): String?

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------

/** Serialized resume state (torrents + DHT nodes), or null. */
expect fun nativeSaveState(handle: Long): ByteArray?

/** Restores resume state into the engine. */
expect fun nativeLoadState(handle: Long, data: ByteArray): Int

// ---------------------------------------------------------------------------
// Polling
// ---------------------------------------------------------------------------

/** Drains pending engine events as one JSON array. */
expect fun nativeTakeEvents(handle: Long): String

/** Drains pending engine log lines as one JSON array. */
expect fun nativeTakeLogs(handle: Long): String
