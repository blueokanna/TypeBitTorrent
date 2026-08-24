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

/** Adds a `.torrent`; returns the hex infohash or null on error. */
expect fun nativeAddTorrent(handle: Long, data: ByteArray, saveDir: String): String?

/** Adds a magnet URI; returns the hex infohash or null on error. */
expect fun nativeAddMagnet(handle: Long, uri: String, saveDir: String): String?

/** Starts (announces + connects) a torrent. 0 = ok, negative = error. */
expect fun nativeStart(handle: Long, hash: String): Int

/** Pauses a torrent. */
expect fun nativePause(handle: Long, hash: String): Int

/** Resumes a paused torrent. */
expect fun nativeResume(handle: Long, hash: String): Int

/** Removes a torrent. 0 = ok, negative = error. */
expect fun nativeRemove(handle: Long, hash: String): Int

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

/** All torrents' persisted state as a JSON array. */
expect fun nativeTorrentStates(handle: Long): String

/** Number of torrents in the engine. */
expect fun nativeTorrentCount(handle: Long): Int

/** Current DHT routing-table size. */
expect fun nativeDhtNodeCount(handle: Long): Int

/** Our 20-byte peer id, hex-encoded. */
expect fun nativePeerId(handle: Long): String

/** Global wire counters JSON: `{"d":down,"u":up}`. */
expect fun nativeTotals(handle: Long): String

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/** Sets global download/upload limits in bytes per second (0 = unlimited). */
expect fun nativeSetGlobalLimits(handle: Long, downBytesPerSec: Long, upBytesPerSec: Long): Int

/** Applies session defaults for torrents added from now on. */
expect fun nativeSetSessionConfig(handle: Long, configJson: String): Int

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
