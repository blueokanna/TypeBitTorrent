package com.typebit.engine

// Shared JVM actuals (compiled for both Android and desktop).
//
// The `external` functions map to the JNI symbols exported by the Rust
// `typebit_native` cdylib; the JVM class is `com.typebit.engine.NativeBridgeKt`
// because these live in NativeBridge.kt (see native/src/jni_glue.rs).

actual external fun nativeCreateEngine(configJson: String, saveDir: String): Long
actual external fun nativeDestroyEngine(handle: Long)
/** Parse `.torrent` bytes → metainfo JSON (no engine add) for the dialog. */
actual external fun nativeParseTorrent(data: ByteArray): String?

actual external fun nativeAddTorrent(handle: Long, data: ByteArray, saveDir: String): String?
actual external fun nativeAddMagnet(handle: Long, uri: String, saveDir: String): String?
actual external fun nativeStart(handle: Long, hash: String): Int
actual external fun nativePause(handle: Long, hash: String): Int
actual external fun nativeResume(handle: Long, hash: String): Int
actual external fun nativeRemove(handle: Long, hash: String): Int
actual external fun nativeProgress(handle: Long, hash: String): Double
actual external fun nativeDownloaded(handle: Long, hash: String): Long
actual external fun nativeIsComplete(handle: Long, hash: String): Boolean
actual external fun nativeTorrentInfo(handle: Long, hash: String): String?
actual external fun nativeTorrentStates(handle: Long): String
actual external fun nativeSnapshot(handle: Long): String
actual external fun nativeTorrentCount(handle: Long): Int
actual external fun nativeDhtNodeCount(handle: Long): Int
actual external fun nativePeerId(handle: Long): String
actual external fun nativeTotals(handle: Long): String
actual external fun nativeSetGlobalLimits(handle: Long, downBytesPerSec: Long, upBytesPerSec: Long): Int
actual external fun nativeSetSessionConfig(handle: Long, configJson: String): Int
actual external fun nativeSaveState(handle: Long): ByteArray?
actual external fun nativeLoadState(handle: Long, data: ByteArray): Int
actual external fun nativeTakeEvents(handle: Long): String
actual external fun nativeTakeLogs(handle: Long): String
