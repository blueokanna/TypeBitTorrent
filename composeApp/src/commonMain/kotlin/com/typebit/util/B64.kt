package com.typebit.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Base64 helpers for persisting binary `.torrent` bytes inside JSON. */
@OptIn(ExperimentalEncodingApi::class)
object B64 {
    fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    fun decode(text: String): ByteArray? =
        runCatching { Base64.decode(text) }.getOrNull()
}
