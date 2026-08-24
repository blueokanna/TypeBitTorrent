package com.typebit.engine

import java.io.File

/**
 * Desktop loader. Resolution order:
 *
 * 1. `java.library.path` — for users who install the DLL system-wide.
 * 2. The classpath resource `/native/typebit_native.dll` — packaged
 *    distributions (scripts/build-desktop.ps1 puts it in desktopMain/resources).
 * 3. A few dev-tree locations, so `./gradlew :composeApp:run` works right
 *    after `scripts/build-desktop.ps1` without re-packaging.
 */
actual fun loadNativeLibrary(): Boolean {
    try {
        System.loadLibrary("typebit_native")
        return true
    } catch (_: UnsatisfiedLinkError) {
        // fall through to the other strategies
    } catch (_: Throwable) {
        return false
    }

    // 2) bundled resource (packaged MSI/EXE/jar)
    try {
        val resource = "/native/typebit_native.dll"
        val stream = NativeLibraryLoader::class.java.getResourceAsStream(resource)
        if (stream != null) {
            val tmp = File.createTempFile("typebit_native", ".dll")
            tmp.deleteOnExit()
            stream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
            System.load(tmp.absolutePath)
            return true
        }
    } catch (_: Throwable) {
        // continue
    }

    // 3) dev-tree candidates
    val candidates = listOf(
        File("native/target/release/typebit_native.dll"),
        File("composeApp/src/desktopMain/resources/native/typebit_native.dll"),
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

/** Tiny marker class so the resource stream lookup has a classloader anchor. */
private object NativeLibraryLoader
