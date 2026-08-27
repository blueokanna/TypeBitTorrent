package com.typebit.data

import com.typebit.platform.FileIO
import com.typebit.platform.Platform
import kotlinx.serialization.json.Json

/**
 * Persists [AppSettings] as pretty JSON in the platform app-data directory.
 *
 * Durability contract (this is the user's configuration — losing it to a
 * crashed save, a killed process or a corrupted file is unacceptable):
 * - Writes are atomic (write-temp-then-rename) so a crash mid-save never
 *   corrupts the file.
 * - Every save first rolls the previous file into `settings.json.bak`, so a
 *   bad write can never destroy the last good configuration.
 * - `load()` falls back to the backup when the main file is missing or
 *   fails to decode.
 */
class SettingsRepository(private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }) {

    private val file = FileIO.child(Platform.appDataDir(), "settings.json")
    private val backup = FileIO.child(Platform.appDataDir(), "settings.json.bak")

    fun load(): AppSettings {
        return loadFrom(file) ?: loadFrom(backup) ?: AppSettings()
    }

    private fun loadFrom(path: String): AppSettings? {
        val text = FileIO.readText(path) ?: return null
        return runCatching { json.decodeFromString<AppSettings>(text) }.getOrNull()
    }

    fun save(settings: AppSettings) {
        // Rolling backup of the previous state — written BEFORE the new one,
        // so the backup always holds the last known-good configuration.
        FileIO.readBytes(file)?.let { FileIO.writeBytesAtomic(backup, it) }
        val text = json.encodeToString(AppSettings.serializer(), settings)
        FileIO.writeTextAtomic(file, text)
    }
}
