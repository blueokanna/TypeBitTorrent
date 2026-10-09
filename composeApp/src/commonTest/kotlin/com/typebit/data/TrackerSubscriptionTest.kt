package com.typebit.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tracker subscriptions: what a public trackerslist document is allowed to
 * put into the engine.
 *
 * These lists are community-maintained text files, so they arrive with
 * comments, "both schemes" markers, blank lines and occasionally a line that
 * is not an announce URL at all. Anything that survives [parseTrackerList] is
 * handed to the engine verbatim, which is why the filter is tested rather
 * than trusted.
 */
class TrackerSubscriptionTest {

    @Test
    fun keeps_only_real_announce_urls() {
        val text =
            """
            # trackerslist - best
            https://tracker.example.org/announce

            udp://open.example.org:6969/announce
            wss://ws.example.org/announce
            magnet:?xt=urn:btih:deadbeef
            ftp://files.example.org/announce
            just some words
            """.trimIndent()
        val parsed = parseSubscriptionList(text)
        assertEquals(
            listOf(
                "https://tracker.example.org/announce",
                "udp://open.example.org:6969/announce",
                "wss://ws.example.org/announce",
            ),
            parsed,
        )
        assertTrue(
            parsed.none { url -> url.any { it.isWhitespace() } },
            "an announce URL containing whitespace would be rejected by every tracker",
        )
    }

    @Test
    fun strips_the_both_schemes_marker_and_deduplicates() {
        val text =
            """
            https://one.example.org/announce*
            https://one.example.org/announce
            http://two.example.org/announce
            // a comment
            """.trimIndent()
        assertEquals(
            listOf("https://one.example.org/announce", "http://two.example.org/announce"),
            parseSubscriptionList(text),
        )
    }

    @Test
    fun the_stored_list_is_bounded() {
        val text = (1..350).joinToString("\n") { "udp://h$it.example.org:80" }
        assertEquals(300, parseSubscriptionList(text).size)
    }

    @Test
    fun an_empty_document_yields_nothing() {
        assertTrue(parseSubscriptionList("").isEmpty())
        assertTrue(parseSubscriptionList("   \n\n#only a comment\n").isEmpty())
    }

    @Test
    fun announced_trackers_are_the_manual_list_plus_the_subscription() {
        val manual = BitTorrentSettings(extraTrackers = "https://a.example.org/announce")
        val subscribed =
            manual.copy(
                subscribedTrackers = "https://b.example.org/announce\nudp://c.example.org:80",
            )
        assertEquals("https://a.example.org/announce", manual.allTrackers)
        assertEquals(
            "https://b.example.org/announce\nudp://c.example.org:80",
            subscribed.allTrackers.substringAfter('\n'),
        )
        // Hand-written list first: it is the user's own ordering.
        assertTrue(subscribed.allTrackers.startsWith("https://a.example.org/announce"))
    }
}
