package com.typebit.data

import com.typebit.util.TrackerListParser

/**
 * Tracker subscriptions — reading a public trackerslist-style document.
 *
 * The parsing rules live in [TrackerListParser] (the same code the desktop
 * *导入* button uses: line-oriented lists as well as URLs embedded in JSON or
 * HTML). This adds the two rules a *subscription* needs on top, because
 * whatever survives here is announced verbatim for every torrent:
 *
 * * the trailing `*` some lists use to mark "HTTP and UDP variants" is not part
 *   of the URL, and
 * * an announce URL never contains whitespace — a line that does is a broken
 *   merge between two entries, not a tracker.
 */
private const val MAX_SUBSCRIBED_TRACKERS = 300

/** Trackers to store from a subscription document, in list order. */
fun parseSubscriptionList(text: String): List<String> =
    TrackerListParser.parse(text)
        .map { it.trimEnd('*') }
        .filter { it.isNotBlank() && it.none { c -> c.isWhitespace() } }
        .distinct()
        .take(MAX_SUBSCRIBED_TRACKERS)
