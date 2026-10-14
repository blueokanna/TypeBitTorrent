package com.typebit.engine

/** Android: the `.so` files ship in `src/androidMain/jniLibs/<abi>/` and load via `System.loadLibrary`. */
actual fun loadNativeLibrary(): Boolean = try {
    System.loadLibrary("typebit_native")
    true
} catch (t: Throwable) {
    android.util.Log.e("TypeBitNative", "loadLibrary(typebit_native) failed", t)
    false
}
