package com.typebit.platform

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

// JVM actual (Android + desktop): `HttpURLConnection` GET with caller-supplied headers and gzip decoding.
// Redirects are followed (search sites 3xx to mirrors); cookie state lives in the caller.
actual fun fetchHttp(
    url: String,
    headers: Map<String, String>,
    timeoutMs: Long,
): HttpFetchResponse? {
    return try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs.toInt()
        conn.readTimeout = timeoutMs.toInt()
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val status = conn.responseCode
        val respHeaders: Map<String, List<String>> =
                conn.headerFields?.mapValues { (_, v) -> v.toList() } ?: emptyMap()
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val bytes =
                if (stream == null) ByteArray(0)
                else {
                    val out = ByteArrayOutputStream()
                    val encoding = conn.getHeaderField("Content-Encoding") ?: ""
                    val input =
                            if (encoding.contains("gzip", ignoreCase = true)) GZIPInputStream(stream)
                            else stream
                    input.use { src ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.toByteArray()
                }
        conn.disconnect()
        HttpFetchResponse(
                status = status,
                headers = respHeaders,
                body = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"),
        )
    } catch (_: Throwable) {
        null
    }
}
