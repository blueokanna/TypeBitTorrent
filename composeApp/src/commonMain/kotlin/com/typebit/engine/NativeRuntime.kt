package com.typebit.engine

expect fun loadNativeLibrary(): Boolean

/**
 * Must stay in sync with `JNI_ABI` in `native/src/lib.rs`: a mismatch must surface as a
 * version error, never as a crash inside a JNI call with a stale signature layout.
 */
const val EXPECTED_BRIDGE_ABI = 4

private var nativeReady = false

/** Must be called before any native call. */
internal fun ensureNativeLoaded() {
    if (!nativeReady) {
        nativeReady = loadNativeLibrary()
        check(nativeReady) { "typebit_native native library failed to load" }
    }
}
