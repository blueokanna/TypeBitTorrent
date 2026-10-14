package com.typebit.platform

/** Atomic file operations; the actual is shared by Android and desktop (`jvmShared`). */
expect object FileIO {
    fun child(parent: String, name: String): String

    fun exists(path: String): Boolean

    fun ensureDir(path: String)

    fun readText(path: String): String?

    fun readBytes(path: String): ByteArray?

    /** Atomic: temp file + rename, so readers never see a torn file. */
    fun writeTextAtomic(path: String, text: String)

    fun writeBytesAtomic(path: String, bytes: ByteArray)

    fun listDir(path: String): List<String>?

    fun size(path: String): Long

    fun delete(path: String): Boolean
}
