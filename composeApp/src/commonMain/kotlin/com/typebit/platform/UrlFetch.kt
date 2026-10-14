package com.typebit.platform

/** Fetches a URL as UTF-8 text (tracker-list import); never throws — null on any failure. */
expect fun fetchUrlText(url: String, timeoutMs: Long): String?
