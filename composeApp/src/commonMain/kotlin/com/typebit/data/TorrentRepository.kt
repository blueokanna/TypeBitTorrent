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
 *
 * The torrent list is the user's download library — losing it would orphan
 * every `.part` file on disk. Like [SettingsRepository], writes are atomic
 * AND roll the previous list into `torrents.json.bak` before overwriting,
 * and `loadRecords()` falls back to the backup when the main file is missing
 * or fails to decode.
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
        // Rolling backup of the previous library — written BEFORE the new
        // one so a bad write can never destroy the last known-good list.
        FileIO.readBytes(recordsFile)?.let { FileIO.writeBytesAtomic(recordsBackup, it) }
        val text = json.encodeToString(records)
        FileIO.writeTextAtomic(recordsFile, text)
    }

    fun loadResumeState(): ByteArray? = FileIO.readBytes(resumeFile)

    fun saveResumeState(bytes: ByteArray) {
        FileIO.writeBytesAtomic(resumeFile, bytes)
    }
}
