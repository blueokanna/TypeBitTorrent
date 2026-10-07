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
 *   fails to decode, and a file that cannot be decoded at all is *kept* as
 *   `settings.json.bad` instead of being overwritten by the defaults. The DNS
 *   upstream list is the kind of thing a NAS owner edits by hand, and a typo
 *   must not cost them the rest of their configuration.
 */
class SettingsRepository(private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }) {

    private val file = FileIO.child(Platform.appDataDir(), "settings.json")
    private val backup = FileIO.child(Platform.appDataDir(), "settings.json.bak")
    private val rejected = FileIO.child(Platform.appDataDir(), "settings.json.bad")

    fun load(): AppSettings {
        return loadFrom(file) ?: loadFrom(backup) ?: AppSettings()
    }

    private fun loadFrom(path: String): AppSettings? {
        val text = FileIO.readText(path) ?: return null
        val parsed = decodeSettings(json, text)
        if (parsed == null && path == file) {
            // Keep what we could not read: a hand-edited file with one typo is
            // still 99% of the user's configuration, and the next `save()` will
            // replace this path with defaults.
            FileIO.readBytes(path)?.let { FileIO.writeBytesAtomic(rejected, it) }
            println("settings: $path could not be decoded; kept as $rejected and using defaults")
        }
        return parsed
    }

    fun save(settings: AppSettings) {
        // Rolling backup of the previous state — written BEFORE the new one,
        // so the backup always holds the last known-good configuration.
        FileIO.readBytes(file)?.let { FileIO.writeBytesAtomic(backup, it) }
        val text = json.encodeToString(AppSettings.serializer(), settings)
        FileIO.writeTextAtomic(file, text)
    }
}

/**
 * Decodes a settings document, tolerating the byte-order mark that every
 * Windows editor adds to a hand-edited file.
 *
 * `decodeFromString` rejects a leading `\uFEFF` outright, so without this a
 * Notepad-edited `settings.json` would be treated as corrupt — the exact file a
 * user edits when they want to add a DNS upstream on a NAS.
 */
internal fun decodeSettings(json: Json, text: String): AppSettings? {
    val cleaned = text.removePrefix("\uFEFF")
    return runCatching { json.decodeFromString<AppSettings>(cleaned) }.getOrNull()
}
