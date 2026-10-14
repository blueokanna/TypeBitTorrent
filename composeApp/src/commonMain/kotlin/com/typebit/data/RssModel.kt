package com.typebit.data

import kotlinx.serialization.Serializable

@Serializable
data class RssItem(
    val title: String = "",
    val link: String = "",
    val description: String = "",
    val pubDate: String = "",
)

@Serializable
data class RssFeed(
    val url: String = "",
    val title: String = "",
    val items: List<RssItem> = emptyList(),
)

/** JVM implementations use the stdlib only (`HttpURLConnection` + `javax.xml`). */
expect fun fetchRssFeed(url: String, timeoutMs: Long): RssFeed?
