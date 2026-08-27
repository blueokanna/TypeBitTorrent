package com.typebit.search

import com.typebit.platform.fetchHttp
import kotlin.random.Random

/**
 * A browser-like HTTP session for the search engines: one cookie jar per
 * engine, realistic Chrome headers, and gzip decoding. `fetchHttp` is the
 * platform seam (JVM shared: Android + desktop).
 *
 * Cookie handling is what lets the engines survive the anti-bot layers that
 * issue a session cookie on the first request (403 → retry with cookie is a
 * common Cloudflare pattern).
 */
class SearchHttpClient(private val timeoutMs: Long = 20_000) {
    private val cookies = LinkedHashMap<String, String>()
    private val rng = Random(System.currentTimeMillis())

    /** Chrome 120-ish UA (kept current enough for modern Cloudflare rules). */
    private val userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * GET `url` and return the decoded body, or null on transport failure.
     * Cookies from previous responses in this session are sent automatically
     * and any `Set-Cookie` from the response is stored.
     */
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

    /** True when the body looks like a Cloudflare JS challenge. */
    fun isChallenge(body: String?): Boolean {
        if (body == null) return false
        val low = body.lowercase()
        return low.contains("cf-browser-verification") ||
                low.contains("just a moment") ||
                low.contains("challenge-platform") ||
                (low.contains("cloudflare") && low.contains("captcha"))
    }

    /** True when the body looks like a 404 / "no results" page. */
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

    /** Small jittered delay between requests (human-like pacing). */
    fun humanDelay(minMs: Long = 600, maxMs: Long = 1800) {
        val wait = minMs + rng.nextLong((maxMs - minMs).coerceAtLeast(1))
        // Blocking sleep is fine: engines run on a background dispatcher.
        Thread.sleep(wait)
    }

    /** Random sub-suffix to vary requests slightly. */
    fun jitterQuery(query: String): String = query.trim()
}

/** One search engine: returns magnet results for `query`, or empty. */
interface TorrentSearchEngine {
    val name: String

    /** Runs on a background dispatcher; must not touch the UI thread. */
    fun search(query: String): List<TorrentSearchResult>
}
