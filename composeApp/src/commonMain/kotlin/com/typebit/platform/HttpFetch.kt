package com.typebit.platform

data class HttpFetchResponse(
    val status: Int = 0,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
) {
    val isSuccess: Boolean get() = status in 200..299
}

// Blocking GET with caller-supplied browser headers and gzip decoding. Returns null on transport failure,
// but a non-2xx body is still returned as a response, so the caller can tell a Cloudflare 403 from a network error.
expect fun fetchHttp(
    url: String,
    headers: Map<String, String>,
    timeoutMs: Long,
): HttpFetchResponse?
