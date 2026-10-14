package com.typebit.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object B64 {
    fun encode(bytes: ByteArray): String = Base64.encode(bytes)

    fun decode(text: String): ByteArray? =
        runCatching { Base64.decode(text) }.getOrNull()
}
