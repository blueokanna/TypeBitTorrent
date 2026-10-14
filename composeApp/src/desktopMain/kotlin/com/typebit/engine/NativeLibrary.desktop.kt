package com.typebit.engine

import java.io.File

// Resolution order: `java.library.path`, the packaged classpath resource `/native/<libname>`, then
// dev-tree locations so `:composeApp:run` works right after a native build.
actual fun loadNativeLibrary(): Boolean {
    val fileName = nativeFileName()
    try {
        System.loadLibrary("typebit_native")
        return true
    } catch (_: UnsatisfiedLinkError) {
    } catch (_: Throwable) {
        return false
    }

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
    }

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
        }
    }
    return false
}

internal fun nativeFileName(): String =
    when {
        System.getProperty("os.name").contains("win", ignoreCase = true) -> "typebit_native.dll"
        System.getProperty("os.name").contains("mac", ignoreCase = true) -> "libtypebit_native.dylib"
        else -> "libtypebit_native.so"
    }

/** Tiny marker class so the resource stream lookup has a classloader anchor. */
private object NativeLibraryLoader
