package com.typebit.engine

/**
 * Loads the platform-specific native library (typebit_native).
 *
 * - Android: `System.loadLibrary("typebit_native")` — the `.so` files are
 *   packaged under `src/androidMain/jniLibs/<abi>/` (see scripts/build-android.ps1).
 * - Desktop: tries `java.library.path` first, then the bundled classpath
 *   resource (packaged distributions), then a few dev-tree locations.
 */
expect fun loadNativeLibrary(): Boolean

/**
 * ABI revision this Kotlin code requires from the native library.
 *
 * Keep in sync with `JNI_ABI` in `native/src/lib.rs`. Bumping either side
 * without the other makes the app report "原生库版本不匹配" instead of
 * crashing inside a JNI call with a stale signature layout.
 */
const val EXPECTED_BRIDGE_ABI = 3

private var nativeReady = false

/** Must be called before any native call. Throws if the library is absent. */
internal fun ensureNativeLoaded() {
    if (!nativeReady) {
        nativeReady = loadNativeLibrary()
        check(nativeReady) { "typebit_native native library failed to load" }
    }
}
