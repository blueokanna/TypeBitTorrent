package com.typebit.util

// Extracts tracker announce URLs from plain-text, JSON or HTML lists (e.g. `ngosang/trackerslist`),
// deduplicated and hard-capped so a hostile endpoint cannot blow up the settings model.
object TrackerListParser {

    /** Upper bound on imported trackers (defensive against abuse). */
    const val MAX_TRACKERS = 500

    private val ASSET_EXT = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".css", ".js", ".ico", ".svg", ".woff", ".woff2")

    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        // JSON escapes `\"` inside string values — unescape so the URL regex sees real URLs.
        var text = raw.replace("\\\"", "\"").replace("\\u002F", "/").replace("\\/", "/")
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

        val seen = LinkedHashSet<String>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.length in 11..512 && looksLikeTracker(t)) seen.add(t)
        }
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
        val lower = url.lowercase()
        for (ext in ASSET_EXT) if (lower.endsWith(ext)) return false
        return true
    }

    private val URL_REGEX = Regex(
        """https?://[^\s"'<>\\]+|udp://[^\s"'<>\\]+|wss?://[^\s"'<>\\]+""",
    )
}
