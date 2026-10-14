package com.typebit.search

// Generic engine for Chinese magnet-index sites: each search-path template is tried on every base URL and
// result blocks are split around magnet URIs, with title/size/seeders picked heuristically; a challenge or
// empty page yields an honest empty list, never fabricated results.
class MagnetIndexEngine(
        override val name: String,
        private val http: SearchHttpClient,
        private val bases: List<String>,
        /** Templates containing `{q}`, tried in order on each base until one returns rows. */
        private val searchPaths: List<String>,
) : TorrentSearchEngine {

    override fun search(query: String): List<TorrentSearchResult> {
        http.humanDelay()
        val q = SearchHtml.urlEncode(query.trim())
        if (q.isEmpty()) return emptyList()
        for (base in bases) {
            for (path in searchPaths) {
                val url = base.trimEnd('/') + path.replace("{q}", q)
                val html = http.get(url, referer = "$base/") ?: continue
                if (http.isChallenge(html)) continue
                if (http.isEmptyResults(html) && !html.contains("magnet:?xt=urn:btih:")) {
                    continue
                }
                val rows = parseRows(html)
                if (rows.isNotEmpty()) return rows
            }
        }
        return emptyList()
    }

    private fun parseRows(html: String): List<TorrentSearchResult> {
        val out = mutableListOf<TorrentSearchResult>()
        val blocks = resultBlocks(html)
        for (block in blocks) {
            val magnet = SearchHtml.magnetIn(block) ?: continue
            val title = bestTitle(block) ?: continue
            if (title.length > 500) continue
            val size = sizeIn(block)
            val (seeds, leeches) = seedLeech(block)
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

    /** Splits the page into one chunk per result: `<tr>` rows when the layout is a table, otherwise a window
     *  around each magnet link. */
    private fun resultBlocks(html: String): List<String> {
        val trRe = Regex("""<tr[^>]*>.*?</tr>""", RegexOption.DOT_MATCHES_ALL)
        val trs = trRe.findAll(html).map { it.value }.toList()
        if (trs.isNotEmpty()) return trs

        // Non-table layout: 512 chars before each magnet link (covers the
        // title anchor + meta cells) plus the magnet itself.
        val out = mutableListOf<String>()
        val magnetRe = Regex("""magnet:\?xt=urn:btih:[0-9a-fA-F]{40}(?:[^"'\s<>]*)*""")
        var lastEnd = 0
        for (m in magnetRe.findAll(html)) {
            val start = (m.range.first - 512).coerceAtLeast(lastEnd)
            val end = (m.range.last + 1 + 256).coerceAtMost(html.length)
            out += html.substring(start, end)
            lastEnd = end
        }
        return out
    }

    private fun bestTitle(block: String): String? {
        val skip =
                setOf(
                        "下载", "磁力", "复制", "详情", "更多", "magnet", "bt", "种子",
                        "高速下载", "解析", "收藏", "报错", "举报",
                )
        var best: String? = null
        for (m in Regex("""<a[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(block)) {
            val text = SearchHtml.stripTags(m.groupValues[1])
            if (text.length < 3) continue
            val low = text.lowercase()
            if (skip.any { low == it || low.contains(it) }) continue
            if (best == null || text.length > best.length) best = text
        }
        if (best == null) {
            // No anchor text (title in `title=` attribute) — fall back to it.
            for (m in Regex("""title="([^"]{3,500})"""").findAll(block)) {
                val text = SearchHtml.decode(m.groupValues[1]).trim()
                if (best == null || text.length > best.length) best = text
            }
        }
        return best?.take(500)
    }

    private fun sizeIn(block: String): String =
            Regex("""(\d+(?:\.\d+)?\s*[KMGTP]?i?B)""", RegexOption.IGNORE_CASE)
                    .find(block)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    .orEmpty()

    private fun seedLeech(block: String): Pair<Int, Int> {
        val seedClasses =
                listOf("seed", "seeds", "success", "num", "upload", "做种")
        val leechClasses = listOf("leech", "leeches", "danger", "下载数", "活种")
        val seed =
                seedClasses
                        .mapNotNull { cls ->
                            Regex("""<[^>]+class="[^"]*${Regex.escape(cls)}[^"]*"[^>]*>\s*(\d+)\s*</[^>]+>""", RegexOption.IGNORE_CASE)
                                    .find(block)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toIntOrNull()
                        }
                        .firstOrNull()
                        ?: 0
        val leech =
                leechClasses
                        .mapNotNull { cls ->
                            Regex("""<[^>]+class="[^"]*${Regex.escape(cls)}[^"]*"[^>]*>\s*(\d+)\s*</[^>]+>""", RegexOption.IGNORE_CASE)
                                    .find(block)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toIntOrNull()
                        }
                        .firstOrNull()
                        ?: 0
        return seed to leech
    }
}
