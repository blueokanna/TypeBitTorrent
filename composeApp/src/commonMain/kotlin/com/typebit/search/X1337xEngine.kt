package com.typebit.search

// 1337x engine: the list page has no magnet URIs, so each top result's detail page is visited (bounded and
// paced) for one. Mirrors are tried in order; a Cloudflare challenge falls through to the next.
class X1337xEngine(private val http: SearchHttpClient) : TorrentSearchEngine {
    override val name = "1337x"

    private val bases =
            listOf(
                    "https://1337x.to",
                    "https://1337x.st",
                    "https://x1337x.ws",
                    "https://1337xx.to",
            )

    override fun search(query: String): List<TorrentSearchResult> {
        http.humanDelay()
        for (base in bases) {
            val url = "$base/search/${SearchHtml.urlEncode(query)}/1/"
            val html = http.get(url, referer = "$base/") ?: continue
            if (http.isChallenge(html)) continue
            val rows = parseRows(html, base)
            if (rows.isEmpty()) continue
            val out = mutableListOf<TorrentSearchResult>()
            for (r in rows.take(10)) {
                val magnet = fetchMagnet(r.detailUrl, base)
                if (magnet != null) out += r.copy(magnet = magnet)
                http.humanDelay(500, 1200)
            }
            return out
        }
        return emptyList()
    }

    private fun parseRows(html: String, base: String): List<TorrentSearchResult> {
        val out = mutableListOf<TorrentSearchResult>()
        val trRe = Regex("""<tr[^>]*>.*?</tr>""", RegexOption.DOT_MATCHES_ALL)
        for (tr in trRe.findAll(html)) {
            val row = tr.value
            if (!row.contains("coll-2")) continue
            val link =
                    Regex("""<td[^>]*class="[^"]*coll-2[^"]*"[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                            .find(row)
                            ?: continue
            val href = link.groupValues[1]
            val title = SearchHtml.stripTags(link.groupValues[2])
            if (title.isBlank()) continue
            if (title.length > 500) continue
            val size =
                    Regex("""<td[^>]*class="[^"]*coll-3[^"]*"[^>]*>\s*([^<]+?)\s*</td>""")
                            .find(row)
                            ?.groupValues
                            ?.get(1)
                            ?.let { SearchHtml.stripTags(it) }
                            .orEmpty()
            val seeds =
                    Regex("""<span[^>]*class="[^"]*seed[^"]*"[^>]*>\s*(\d+)\s*</span>""")
                            .find(row)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?: 0
            val leeches =
                    Regex("""<span[^>]*class="[^"]*leeches[^"]*"[^>]*>\s*(\d+)\s*</span>""")
                            .find(row)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?: 0
            out +=
                    TorrentSearchResult(
                            title = title,
                            magnet = "",
                            size = size,
                            seeds = seeds,
                            leeches = leeches,
                            source = name,
                            detailUrl = SearchHtml.absolute(base, href),
                    )
        }
        return out
    }

    private fun fetchMagnet(detailUrl: String, base: String): String? {
        val html = http.get(detailUrl, referer = "$base/search/") ?: return null
        if (http.isChallenge(html)) return null
        return SearchHtml.magnetIn(html)
    }
}
