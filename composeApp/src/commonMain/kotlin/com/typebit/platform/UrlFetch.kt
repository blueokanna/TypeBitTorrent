package com.typebit.platform

/**
 * Fetches a URL's body as UTF-8 text. Used by the tracker-list importer
 * (and available for any future remote-config feature). Returns null on any
 * failure — never throws.
 */
expect fun fetchUrlText(url: String, timeoutMs: Long): String?
