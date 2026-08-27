package com.typebit.platform

/**
 * Minimal HTTP response for the auto-search client (status + headers + body).
 */
data class HttpFetchResponse(
    val status: Int = 0,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * Blocking HTTP GET with browser-like behaviour for the auto search client.
 * Headers are caller-supplied (User-Agent, Accept, Accept-Language,
 * Referer, Cookie…); the body is decoded from gzip when the server
 * compressed it. Returns null on transport failure; a non-2xx status is
 * still returned as a response so the caller can distinguish a Cloudflare
 * challenge (403) from a network error.
 */
expect fun fetchHttp(
    url: String,
    headers: Map<String, String>,
    timeoutMs: Long,
): HttpFetchResponse?
