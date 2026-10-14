package com.typebit.data

import com.typebit.platform.FileIO
import com.typebit.platform.Platform
import kotlinx.serialization.json.Json

/**
 * Durability contract: atomic writes, every save rolls `settings.json` into `settings.json.bak`
 * first, and `load()` falls back to that backup. An undecodable file is preserved as
 * `settings.json.bad` rather than silently replaced — users hand-edit this file (DNS upstreams).
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
            // Keep the unreadable file: the next `save()` would overwrite it with defaults.
            FileIO.readBytes(path)?.let { FileIO.writeBytesAtomic(rejected, it) }
            println("settings: $path could not be decoded; kept as $rejected and using defaults")
        }
        return parsed
    }

    fun save(settings: AppSettings) {
        // Back up before writing: the backup must hold the last known-good configuration.
        FileIO.readBytes(file)?.let { FileIO.writeBytesAtomic(backup, it) }
        val text = json.encodeToString(AppSettings.serializer(), settings)
        FileIO.writeTextAtomic(file, text)
    }
}

/**
 * Strips the UTF-8 BOM that `decodeFromString` rejects outright, so a hand-edited
 * `settings.json` saved by a Windows editor is not mistaken for a corrupt file.
 */
internal fun decodeSettings(json: Json, text: String): AppSettings? {
    val cleaned = text.removePrefix("\uFEFF")
    return runCatching { json.decodeFromString<AppSettings>(cleaned) }.getOrNull()
}
