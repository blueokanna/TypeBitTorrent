package com.typebit.data

import com.typebit.engine.ReceiptDto
import com.typebit.platform.FileIO
import com.typebit.platform.Platform
import kotlinx.serialization.json.Json

/** One exported receipt on disk. */
data class ReceiptFile(
    /** Absolute path of the saved receipt JSON. */
    val path: String,
    /** The receipt, when the file decodes cleanly (null = corrupt/foreign). */
    val receipt: ReceiptDto?,
    /** Raw JSON content (used for re-verification). */
    val json: String,
)

/** Result of an export attempt: either a saved receipt or a truthful error. */
data class ReceiptExportResult(
    val receipt: ReceiptDto? = null,
    /** Absolute path of the saved file, when the export succeeded. */
    val path: String? = null,
    /** Human-readable error, when the export failed (e.g. coverage < 90%). */
    val error: String? = null,
) {
    val isSuccess: Boolean get() = receipt != null && path != null
}

/**
 * Persists exported proof-of-download receipts as standalone JSON files in
 * `<appData>/receipts/`. Every file is self-contained (signature + payload),
 * so a receipt can be moved to another machine and verified there.
 *
 * Durability follows the settings contract: atomic writes (temp + rename),
 * one file per torrent hash (a new export replaces the old one for the same
 * content), and tolerant reads — a file that fails to decode is still listed
 * (as `receipt = null`) so the user can delete or inspect it, never silently
 * dropped.
 */
class ReceiptRepository(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val dir: String get() = FileIO.child(Platform.appDataDir(), "receipts")

    /** Sanitize a torrent hash into a safe filename fragment. */
    private fun fileNameFor(hash: String): String {
        val safe = hash.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return "${safe.ifBlank { "unknown" }}.receipt.json"
    }

    /** Path where the receipt for `hash` is (or will be) stored. */
    fun pathFor(hash: String): String = FileIO.child(dir, fileNameFor(hash))

    /** Save a receipt JSON atomically. Returns the absolute path. */
    fun save(hash: String, receiptJson: String): String {
        FileIO.ensureDir(dir)
        val path = pathFor(hash)
        FileIO.writeTextAtomic(path, receiptJson)
        return path
    }

    /** All saved receipts (sorted newest first by file name suffix). */
    fun list(): List<ReceiptFile> {
        val files = FileIO.listDir(dir).orEmpty()
        return files
            .filter { it.endsWith(".receipt.json") }
            .sortedDescending()
            .map { path ->
                val text = FileIO.readText(path).orEmpty()
                val receipt = runCatching {
                    json.decodeFromString<ReceiptDto>(text)
                }.getOrNull()
                ReceiptFile(path = path, receipt = receipt, json = text)
            }
    }

    /** Receipts whose content root (infohash) matches `hash`. */
    fun listFor(hash: String): List<ReceiptFile> =
        list().filter { it.receipt?.content_root?.equals(hash, ignoreCase = true) == true }

    /** Delete a saved receipt file. Returns false when it did not exist. */
    fun delete(path: String): Boolean = FileIO.delete(path)

    /** Raw JSON content of a saved receipt, or null. */
    fun read(path: String): String? = FileIO.readText(path)
}
