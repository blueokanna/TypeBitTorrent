package com.typebit.data

import com.typebit.platform.FileIO
import com.typebit.platform.Platform
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists the user's RSS feed subscriptions. */
class RssRepository(
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    private val file = FileIO.child(Platform.appDataDir(), "rss_feeds.json")

    fun loadFeedUrls(): List<String> {
        val text = FileIO.readText(file) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(text) }.getOrDefault(emptyList())
    }

    fun saveFeedUrls(urls: List<String>) {
        FileIO.writeTextAtomic(file, json.encodeToString(urls))
    }
}
