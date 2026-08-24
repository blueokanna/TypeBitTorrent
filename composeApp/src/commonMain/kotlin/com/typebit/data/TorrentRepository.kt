package com.typebit.data

import com.typebit.model.TorrentRecord
import com.typebit.platform.FileIO
import com.typebit.platform.Platform
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the app-level torrent list (which torrents exist, where they were
 * added from, their category/tags) and the engine resume blob (verified-piece
 * bitfields + DHT routing table). Both survive app restarts.
 */
class TorrentRepository(
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    private val recordsFile = FileIO.child(Platform.appDataDir(), "torrents.json")
    private val resumeFile = FileIO.child(Platform.appDataDir(), "resume.bin")

    fun loadRecords(): List<TorrentRecord> {
        val text = FileIO.readText(recordsFile) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<TorrentRecord>>(text)
        }.getOrDefault(emptyList())
    }

    fun saveRecords(records: List<TorrentRecord>) {
        val text = json.encodeToString(records)
        FileIO.writeTextAtomic(recordsFile, text)
    }

    fun loadResumeState(): ByteArray? = FileIO.readBytes(resumeFile)

    fun saveResumeState(bytes: ByteArray) {
        FileIO.writeBytesAtomic(resumeFile, bytes)
    }
}
