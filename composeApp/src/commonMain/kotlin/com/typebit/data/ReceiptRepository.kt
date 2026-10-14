package com.typebit.data

import com.typebit.engine.ReceiptDto
import com.typebit.platform.FileIO
import com.typebit.platform.Platform
import kotlinx.serialization.json.Json

data class ReceiptFile(
    val path: String,
    /** Null when the file failed to decode — corrupt or foreign. */
    val receipt: ReceiptDto?,
    val json: String,
)

data class ReceiptExportResult(
    val receipt: ReceiptDto? = null,
    val path: String? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = receipt != null && path != null
}

/**
 * One self-contained file (signature + payload, so it verifies anywhere) per torrent hash under
 * `<appData>/receipts/`; a new export replaces the previous file for the same hash.
 */
class ReceiptRepository(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val dir: String get() = FileIO.child(Platform.appDataDir(), "receipts")

    private fun fileNameFor(hash: String): String {
        val safe = hash.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return "${safe.ifBlank { "unknown" }}.receipt.json"
    }

    fun pathFor(hash: String): String = FileIO.child(dir, fileNameFor(hash))

    fun save(hash: String, receiptJson: String): String {
        FileIO.ensureDir(dir)
        val path = pathFor(hash)
        FileIO.writeTextAtomic(path, receiptJson)
        return path
    }

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

    /** `content_root` is the receipt's infohash. */
    fun listFor(hash: String): List<ReceiptFile> =
        list().filter { it.receipt?.content_root?.equals(hash, ignoreCase = true) == true }

    fun delete(path: String): Boolean = FileIO.delete(path)

    fun read(path: String): String? = FileIO.readText(path)
}
