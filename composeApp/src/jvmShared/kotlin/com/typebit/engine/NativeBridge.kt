package com.typebit.engine

// Shared JVM actuals (Android + desktop). The `external` functions map to the JNI symbols the Rust
// `typebit_native` cdylib exports for class `NativeBridgeKt` (see native/src/jni_glue.rs).

actual external fun nativeBridgeAbi(): Int

actual external fun nativeCreateEngine(configJson: String, saveDir: String): Long
actual external fun nativeDestroyEngine(handle: Long)
actual external fun nativeParseTorrent(data: ByteArray): String?
actual external fun nativeMakeTorrent(optionsJson: String): ByteArray?

actual external fun nativeMakeTorrentProgress(): String

actual external fun nativeMakeTorrentCancel(): Int

/** Verifies on-disk data against the piece hashes ("重新校验") — blocking, so call it off the UI thread.
 *  Returns the verified piece count, negative on error. */
actual external fun nativeRecheckData(handle: Long, hash: String): Int

actual external fun nativeRecheckProgress(): String

actual external fun nativeRecheckCancel(): Int

actual external fun nativeAddTorrent(handle: Long, data: ByteArray, saveDir: String, prioritiesJson: String): String?
actual external fun nativeAddMagnet(handle: Long, uri: String, saveDir: String): String?
actual external fun nativeStart(handle: Long, hash: String): Int
actual external fun nativeSetFilePriorities(handle: Long, hash: String, prioritiesJson: String): Int
actual external fun nativeSetHoldData(handle: Long, hash: String, hold: Int): Int
actual external fun nativePause(handle: Long, hash: String): Int
actual external fun nativeResume(handle: Long, hash: String): Int
actual external fun nativeRemove(handle: Long, hash: String): Int
actual external fun nativeRenameFile(handle: Long, hash: String, file: Int, name: String): Int
actual external fun nativeRenameTorrent(handle: Long, hash: String, name: String): Int
actual external fun nativeProgress(handle: Long, hash: String): Double
actual external fun nativeDownloaded(handle: Long, hash: String): Long
actual external fun nativeIsComplete(handle: Long, hash: String): Boolean
actual external fun nativeTorrentInfo(handle: Long, hash: String): String?

actual external fun nativeTorrentInfoRaw(handle: Long, hash: String): String?
actual external fun nativeTorrentStates(handle: Long): String
actual external fun nativePeers(handle: Long, hash: String): String
actual external fun nativeSnapshot(handle: Long): String
actual external fun nativeTorrentCount(handle: Long): Int
actual external fun nativeDhtNodeCount(handle: Long): Int
actual external fun nativePeerId(handle: Long): String
actual external fun nativeTotals(handle: Long): String

actual external fun nativeStats(handle: Long): String
actual external fun nativeSetGlobalLimits(handle: Long, downBytesPerSec: Long, upBytesPerSec: Long): Int
actual external fun nativeSetSessionConfig(handle: Long, configJson: String): Int
actual external fun nativeSetFilePriority(handle: Long, hash: String, file: Int, priority: Int): Int
actual external fun nativeFilePriorities(handle: Long, hash: String): String?
actual external fun nativeAddTracker(handle: Long, hash: String, url: String): Int
actual external fun nativeRemoveTracker(handle: Long, hash: String, url: String): Int
actual external fun nativeTrackers(handle: Long, hash: String): String?
actual external fun nativeExportReceipt(
    handle: Long,
    hash: String,
    rangeStart: Long,
    rangeEnd: Long,
    epochStart: Long,
    epochEnd: Long,
): String?
actual external fun nativeVerifyReceipt(handle: Long, json: String): String?
actual external fun nativeSaveState(handle: Long): ByteArray?
actual external fun nativeLoadState(handle: Long, data: ByteArray): Int
actual external fun nativeTakeEvents(handle: Long): String
actual external fun nativeTakeLogs(handle: Long): String

// Windows system integration (firewall / ICS) — engine-independent.
actual external fun nativeFirewallAdd(port: Int): String
actual external fun nativeFirewallAddElevated(port: Int): String
actual external fun nativeFirewallRemove(port: Int): String
actual external fun nativeFirewallStatus(port: Int): String
actual external fun nativeIcsStatus(): String
actual external fun nativeIcsEnable(): String
actual external fun nativeIcsDisable(): String
