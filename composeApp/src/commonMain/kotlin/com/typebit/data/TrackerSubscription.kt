package com.typebit.data

import com.typebit.util.TrackerListParser

// These trackers are announced verbatim for every torrent, so on top of [TrackerListParser]
// drop the `*` "HTTP + UDP variants" marker and any line containing whitespace (a broken merge).
private const val MAX_SUBSCRIBED_TRACKERS = 300

fun parseSubscriptionList(text: String): List<String> =
    TrackerListParser.parse(text)
        .map { it.trimEnd('*') }
        .filter { it.isNotBlank() && it.none { c -> c.isWhitespace() } }
        .distinct()
        .take(MAX_SUBSCRIBED_TRACKERS)
