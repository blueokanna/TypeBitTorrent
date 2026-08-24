package com.typebit.platform

import java.net.HttpURLConnection
import java.net.URL

actual fun fetchUrlText(url: String, timeoutMs: Long): String? {
    return try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs.toInt()
        conn.readTimeout = timeoutMs.toInt()
        conn.setRequestProperty("User-Agent", "TypeBit/0.1 (+https://github.com/blueokanna/TypeBitTorrent)")
        conn.setRequestProperty("Accept", "text/plain, application/json, text/html, */*")
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return null
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        // UTF-8 with BOM tolerance; HTML pages are re-decoded by the parser.
        String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    } catch (_: Throwable) {
        null
    }
}
