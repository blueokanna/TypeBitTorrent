package com.typebit.search

/**
 * The Pirate Bay search engine.
 *
 * The search result page carries the magnet URIs directly (no detail-page
 * round trip). Mirrors are tried in order because TPB rotates them; a
 * Cloudflare / geo-block on one falls through to the next.
 */
class PirateBayEngine(private val http: SearchHttpClient) : TorrentSearchEngine {
    override val name = "The Pirate Bay"

    private val bases =
            listOf(
                    "https://thepiratebay.org",
                    "https://thepiratebay.su",
                    "https://pirateproxy.live",
            )

    override fun search(query: String): List<TorrentSearchResult> {
        http.humanDelay()
        for (base in bases) {
            val url = "$base/search.php?q=${SearchHtml.urlEncode(query)}"
            val html = http.get(url, referer = "$base/") ?: continue
            if (http.isChallenge(html)) continue
            val rows = parseRows(html)
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    private fun parseRows(html: String): List<TorrentSearchResult> {
        val out = mutableListOf<TorrentSearchResult>()
        val trRe = Regex("""<tr[^>]*>.*?</tr>""", RegexOption.DOT_MATCHES_ALL)
        for (tr in trRe.findAll(html)) {
            val row = tr.value
            val magnet = SearchHtml.magnetIn(row) ?: continue
            // Title: <div class="detName"><a href="/torrent/...">Title</a></div>
            val title = SearchHtml.tagText(row, "a", """href="/torrent/""")
            if (title.isBlank()) continue
            if (title.length > 500) continue
            // Size: <font class="detDesc">Uploaded ..., Size 1.2GiB, ...</font>
            var size = ""
            val desc =
                    Regex("""<font[^>]*class="[^"]*detDesc[^"]*"[^>]*>(.*?)</font>""", RegexOption.DOT_MATCHES_ALL)
                            .find(row)
                            ?.groupValues
                            ?.get(1)
                            ?.let { SearchHtml.stripTags(it) }
                            .orEmpty()
            val sizeRe = Regex("""Size ([\d.,]+\s*[KMGTP]?i?B)""", RegexOption.IGNORE_CASE)
            sizeRe.find(desc)?.let { size = it.groupValues[1] }
            val date =
                    Regex("""Uploaded\s+(.+?)(?:,|$)""", RegexOption.IGNORE_CASE)
                            .find(desc)
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                            .orEmpty()
            // Seeders / leechers: <td class="vertTh"><center>N</center></td>
            // — the first two such cells after the title are seeds/leeches.
            val verts =
                    Regex("""<td[^>]*class="[^"]*vertTh[^"]*"[^>]*>\s*<center>\s*(\d+)\s*</center>""")
                            .findAll(row)
                            .map { it.groupValues[1].toIntOrNull() ?: 0 }
                            .toList()
            val seeds = verts.getOrNull(0) ?: 0
            val leeches = verts.getOrNull(1) ?: 0
            out +=
                    TorrentSearchResult(
                            title = title,
                            magnet = magnet,
                            size = size,
                            seeds = seeds,
                            leeches = leeches,
                            date = date,
                            source = name,
                    )
        }
        return out
    }
}
