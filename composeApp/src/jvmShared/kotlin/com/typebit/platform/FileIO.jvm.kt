package com.typebit.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

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

    actual fun writeTextAtomic(path: String, text: String) = writeBytesAtomic(path, text.encodeToByteArray())

    actual fun writeBytesAtomic(path: String, bytes: ByteArray) {
        val target = File(path).absoluteFile.toPath()
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".${target.fileName}-", ".tmp")
        try {
            Files.write(tmp, bytes)
            Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    actual fun listDir(path: String): List<String>? {
        val dir = File(path)
        if (!dir.isDirectory) return null
        return dir.list()?.sorted()
    }

    actual fun size(path: String): Long {
        val f = File(path)
        return if (f.isFile) f.length() else -1L
    }

    actual fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)
}
