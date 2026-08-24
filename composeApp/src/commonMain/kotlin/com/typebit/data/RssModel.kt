package com.typebit.data

import kotlinx.serialization.Serializable

/** A single RSS/Atom article. */
@Serializable
data class RssItem(
    val title: String = "",
    val link: String = "",
    val description: String = "",
    val pubDate: String = "",
)

/** A feed plus its fetched articles. */
@Serializable
data class RssFeed(
    val url: String = "",
    val title: String = "",
    val items: List<RssItem> = emptyList(),
)

/**
 * Fetches and parses an RSS/Atom feed. Implemented on the JVM with the
 * standard library (`HttpURLConnection` + `javax.xml`), so it works on both
 * Android and desktop without extra dependencies.
 */
expect fun fetchRssFeed(url: String, timeoutMs: Long): RssFeed?
