package com.typebit.search

// Nyaa (nyaa.si) engine: scrapes the result table for title/size/seeders/leechers and the magnet already on
// the list page; a Cloudflare challenge yields an empty result, never fabricated data.
class NyaaEngine(private val http: SearchHttpClient) : TorrentSearchEngine {
    override val name = "Nyaa"
    private val base = "https://nyaa.si"

    override fun search(query: String): List<TorrentSearchResult> {
        http.humanDelay()
        val url = "$base/?f=0&c=0_0&q=${SearchHtml.urlEncode(query)}"
        val html = http.get(url, referer = "$base/") ?: return emptyList()
        if (http.isChallenge(html)) return emptyList()
        return parseRows(html)
    }

    private fun parseRows(html: String): List<TorrentSearchResult> {
        val out = mutableListOf<TorrentSearchResult>()
        val trRe = Regex("""<tr[^>]*>.*?</tr>""", RegexOption.DOT_MATCHES_ALL)
        for (tr in trRe.findAll(html)) {
            val row = tr.value
            val magnet = SearchHtml.magnetIn(row) ?: continue
            var title = SearchHtml.tagText(row, "a", """href="/view/\d+""")
            if (title.isBlank()) title = SearchHtml.tagText(row, "a")
            if (title.isBlank()) continue
            if (title.length > 500) continue // defensive: not a real title
            val size =
                    Regex("""<td[^>]*>\s*([\d.,]+\s*[KMGTP]?i?B)\s*</td>""")
                            .find(row)
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                            .orEmpty()
            val seeds =
                    Regex("""<span[^>]*class="[^"]*text-success[^"]*"[^>]*>\s*(\d+)\s*</span>""")
                            .find(row)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?: 0
            val leeches =
                    Regex("""<span[^>]*class="[^"]*text-danger[^"]*"[^>]*>\s*(\d+)\s*</span>""")
                            .find(row)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?: 0
            out +=
                    TorrentSearchResult(
                            title = title,
                            magnet = magnet,
                            size = size,
                            seeds = seeds,
                            leeches = leeches,
                            source = name,
                    )
        }
        return out
    }
}
