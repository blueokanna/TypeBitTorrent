package com.typebit.engine

/**
 * Android loader: the `.so` files live under `src/androidMain/jniLibs/<abi>/`
 * and are resolved by `System.loadLibrary` at runtime.
 */
actual fun loadNativeLibrary(): Boolean = try {
    System.loadLibrary("typebit_native")
    true
} catch (t: Throwable) {
    android.util.Log.e("TypeBitNative", "loadLibrary(typebit_native) failed", t)
    false
}
