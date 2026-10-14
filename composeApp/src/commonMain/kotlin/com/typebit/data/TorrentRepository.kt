package com.typebit.data

import com.typebit.model.TorrentRecord
import com.typebit.platform.FileIO
import com.typebit.platform.Platform
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `resume.bin` is the engine's resume blob: verified-piece bitfields plus the DHT routing table.
 */
class TorrentRepository(
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    private val recordsFile = FileIO.child(Platform.appDataDir(), "torrents.json")
    private val recordsBackup = FileIO.child(Platform.appDataDir(), "torrents.json.bak")
    private val resumeFile = FileIO.child(Platform.appDataDir(), "resume.bin")

    fun loadRecords(): List<TorrentRecord> {
        return loadFrom(recordsFile) ?: loadFrom(recordsBackup) ?: emptyList()
    }

    private fun loadFrom(path: String): List<TorrentRecord>? {
        val text = FileIO.readText(path) ?: return null
        return runCatching {
            json.decodeFromString<List<TorrentRecord>>(text)
        }.getOrNull()
    }

    fun saveRecords(records: List<TorrentRecord>) {
        // Back up first: a bad write must never destroy the last known-good library.
        FileIO.readBytes(recordsFile)?.let { FileIO.writeBytesAtomic(recordsBackup, it) }
        val text = json.encodeToString(records)
        FileIO.writeTextAtomic(recordsFile, text)
    }

    fun loadResumeState(): ByteArray? = FileIO.readBytes(resumeFile)

    fun saveResumeState(bytes: ByteArray) {
        FileIO.writeBytesAtomic(resumeFile, bytes)
    }
}
