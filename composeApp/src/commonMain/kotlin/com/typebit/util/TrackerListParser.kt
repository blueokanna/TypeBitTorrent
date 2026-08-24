package com.typebit.util

/**
 * Extracts BitTorrent tracker announce URLs from arbitrary fetched text.
 *
 * Tolerant parser covering the three common formats served by tracker-list
 * endpoints (e.g. `ngosang/trackerslist`):
 * 1. plain text — one announce URL per line;
 * 2. JSON — an array of URL strings (or any object containing them);
 * 3. HTML — URLs embedded in markup.
 *
 * Output is deduplicated, trimmed, filtered of non-tracker assets and
 * hard-capped so a hostile endpoint cannot blow up the settings model.
 */
object TrackerListParser {

    /** Upper bound on imported trackers (defensive against abuse). */
    const val MAX_TRACKERS = 500

    private val ASSET_EXT = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".css", ".js", ".ico", ".svg", ".woff", ".woff2")

    /**
     * Parses [raw] into a list of announce URLs. Never throws.
     * URLs must be `http(s)://`, `udp://`, `ws://` or `wss://`.
     */
    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        // JSON escapes `\"` inside string values — unescape so URL regex
        // sees the real URLs, then also handle HTML entities.
        var text = raw.replace("\\\"", "\"").replace("\\u002F", "/").replace("\\/", "/")
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

        val seen = LinkedHashSet<String>()
        // 1) Whole-line style first (fast path for plain lists).
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.length in 11..512 && looksLikeTracker(t)) seen.add(t)
        }
        // 2) Embedded URLs inside JSON/HTML that were not on their own line.
        URL_REGEX.findAll(text).forEach { m ->
            val u = m.value
            if (u.length in 11..512 && looksLikeTracker(u)) seen.add(u)
        }
        return seen.take(MAX_TRACKERS)
    }

    private fun looksLikeTracker(url: String): Boolean {
        if (!(url.startsWith("http://") || url.startsWith("https://") ||
                url.startsWith("udp://") || url.startsWith("ws://") || url.startsWith("wss://"))
        ) return false
        // Skip obviously non-tracker assets.
        val lower = url.lowercase()
        for (ext in ASSET_EXT) if (lower.endsWith(ext)) return false
        return true
    }

    private val URL_REGEX = Regex(
        """https?://[^\s"'<>\\]+|udp://[^\s"'<>\\]+|wss?://[^\s"'<>\\]+""",
    )
}
