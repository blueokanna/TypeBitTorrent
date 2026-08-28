package com.typebit.platform

import java.io.File

/**
 * JVM-shared actual: Android and desktop both run on a JVM, so `java.io.File`
 * semantics are identical.
 */
actual object FileIO {
    actual fun child(parent: String, name: String): String = File(parent, name).absolutePath

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun ensureDir(path: String) {
        File(path).mkdirs()
    }

    actual fun readText(path: String): String? =
        runCatching { File(path).readText() }.getOrNull()

    actual fun readBytes(path: String): ByteArray? =
        runCatching { File(path).readBytes() }.getOrNull()

    actual fun writeTextAtomic(path: String, text: String) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        // Atomic on the same volume; on failure the previous file survives.
        if (!tmp.renameTo(target)) {
            tmp.delete()
            target.writeText(text)
        }
    }

    actual fun writeBytesAtomic(path: String, bytes: ByteArray) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            target.writeBytes(bytes)
        }
    }

    actual fun listDir(path: String): List<String>? {
        val dir = File(path)
        if (!dir.isDirectory) return null
        return dir.list()?.sorted()
    }

    actual fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)
}
