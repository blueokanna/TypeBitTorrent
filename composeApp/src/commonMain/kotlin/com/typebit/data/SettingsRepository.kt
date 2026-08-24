package com.typebit.data

import com.typebit.platform.FileIO
import com.typebit.platform.Platform
import kotlinx.serialization.json.Json

/**
 * Persists [AppSettings] as pretty JSON in the platform app-data directory.
 * Writes are atomic (write-temp-then-rename) so a crash mid-save never
 * corrupts the user's configuration.
 */
class SettingsRepository(private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }) {

    private val file = FileIO.child(Platform.appDataDir(), "settings.json")

    fun load(): AppSettings {
        val text = FileIO.readText(file) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(text) }.getOrDefault(AppSettings())
    }

    fun save(settings: AppSettings) {
        val text = json.encodeToString(AppSettings.serializer(), settings)
        FileIO.writeTextAtomic(file, text)
    }
}
