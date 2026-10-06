package com.typebit.engine

import java.io.File

/**
 * Desktop/JVM loader — platform aware, because the same app image now also
 * ships for Linux (fnOS, Unraid and plain NAS/desktop installs).
 *
 * Resolution order:
 *
 * 1. `java.library.path` — for users who install the library system-wide.
 * 2. The classpath resource `/native/<libname>` — packaged distributions
 *    (`scripts/build-desktop.ps1` for Windows, `scripts/build-linux.sh` for
 *    Linux/macOS put the freshly built library in `desktopMain/resources/native`).
 * 3. A few dev-tree locations, so `./gradlew :composeApp:run` works right
 *    after a native build without re-packaging.
 */
actual fun loadNativeLibrary(): Boolean {
    val fileName = nativeFileName()
    try {
        System.loadLibrary("typebit_native")
        return true
    } catch (_: UnsatisfiedLinkError) {
        // fall through to the other strategies
    } catch (_: Throwable) {
        return false
    }

    // 2) bundled resource (packaged MSI/EXE/DEB/app image)
    try {
        val stream = NativeLibraryLoader::class.java.getResourceAsStream("/native/$fileName")
        if (stream != null) {
            val suffix = fileName.substringAfterLast('.', "lib")
            val tmp = File.createTempFile("typebit_native", ".$suffix")
            tmp.deleteOnExit()
            stream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            System.load(tmp.absolutePath)
            return true
        }
    } catch (_: Throwable) {
        // continue
    }

    // 3) dev-tree candidates
    val candidates =
        listOf(
            File("native/target/release/$fileName"),
            File("composeApp/src/desktopMain/resources/native/$fileName"),
        )
    for (f in candidates) {
        try {
            if (f.isFile) {
                System.load(f.absolutePath)
                return true
            }
        } catch (_: Throwable) {
            // continue
        }
    }
    return false
}

/** `libtypebit_native.so` on Linux/Android, `.dylib` on macOS, `.dll` on Windows. */
internal fun nativeFileName(): String =
    when {
        System.getProperty("os.name").contains("win", ignoreCase = true) -> "typebit_native.dll"
        System.getProperty("os.name").contains("mac", ignoreCase = true) -> "libtypebit_native.dylib"
        else -> "libtypebit_native.so"
    }

/** Tiny marker class so the resource stream lookup has a classloader anchor. */
private object NativeLibraryLoader
