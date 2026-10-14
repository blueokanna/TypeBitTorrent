package com.typebit.search

import com.typebit.platform.fetchHttp
import kotlin.random.Random

// Browser-like session for the engines: one cookie jar per engine, Chrome headers, gzip decoding. Keeping
// cookies is what lets them survive anti-bot layers that issue a session cookie on the first request
// (403 → retry with the cookie).
class SearchHttpClient(private val timeoutMs: Long = 20_000) {
    private val cookies = LinkedHashMap<String, String>()
    private val rng = Random(System.currentTimeMillis())

    private val userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** GETs `url` and returns the decoded body, or null on transport failure; cookies from this session are
     *  sent and any `Set-Cookie` from the response is stored. */
    fun get(url: String, referer: String? = null): String? {
        val headers =
                mutableMapOf(
                        "User-Agent" to userAgent,
                        "Accept" to
                                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache",
                )
        referer?.let { headers["Referer"] = it }
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        val resp = fetchHttp(url, headers, timeoutMs) ?: return null
        absorbCookies(resp.headers)
        return resp.body
    }

    fun isChallenge(body: String?): Boolean {
        if (body == null) return false
        val low = body.lowercase()
        return low.contains("cf-browser-verification") ||
                low.contains("just a moment") ||
                low.contains("challenge-platform") ||
                (low.contains("cloudflare") && low.contains("captcha"))
    }

    fun isEmptyResults(body: String?): Boolean {
        if (body == null) return true
        val low = body.lowercase()
        return low.contains("no results") ||
                low.contains("not found") ||
                low.contains("nothing found") ||
                low.contains("没有找到") ||
                low.contains("no torrents")
    }

    private fun absorbCookies(headers: Map<String, List<String>>) {
        headers["Set-Cookie"]?.forEach { setCookie ->
            val pair = setCookie.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq > 0) {
                cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
        }
    }

    fun humanDelay(minMs: Long = 600, maxMs: Long = 1800) {
        val wait = minMs + rng.nextLong((maxMs - minMs).coerceAtLeast(1))
        // Blocking sleep is fine: engines run on a background dispatcher.
        Thread.sleep(wait)
    }

    fun jitterQuery(query: String): String = query.trim()
}

interface TorrentSearchEngine {
    val name: String

    fun search(query: String): List<TorrentSearchResult>
}
