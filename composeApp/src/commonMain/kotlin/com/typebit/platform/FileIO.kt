package com.typebit.platform

/**
 * Minimal, atomic file operations. Both targets are JVM, so the actual lives
 * in `jvmShared` and is shared verbatim by Android and desktop.
 */
expect object FileIO {
    fun child(parent: String, name: String): String

    fun exists(path: String): Boolean

    fun ensureDir(path: String)

    fun readText(path: String): String?

    fun readBytes(path: String): ByteArray?

    /** Writes via a temp file + rename so readers never see a torn file. */
    fun writeTextAtomic(path: String, text: String)

    /** Writes via a temp file + rename so readers never see a torn file. */
    fun writeBytesAtomic(path: String, bytes: ByteArray)

    /** Lists directory entry names (files and folders), or null when absent. */
    fun listDir(path: String): List<String>?

    /** Deletes a file. Returns false when it did not exist (or failed). */
    fun delete(path: String): Boolean
}
