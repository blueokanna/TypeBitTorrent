package com.typebit.ui.util

/**
 * Minimal RFC 3986 percent-encoding (UTF-8), used for building search URLs.
 * Kept dependency-free and unit-testable.
 */
object UrlCodec {
    private const val HEX = "0123456789ABCDEF"

    fun encode(input: String): String {
        val bytes = input.encodeToByteArray()
        val out = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                isUnreserved(c) -> out.append(c.toChar())
                else -> {
                    out.append('%')
                    out.append(HEX[c shr 4])
                    out.append(HEX[c and 0x0F])
                }
            }
        }
        return out.toString()
    }

    private fun isUnreserved(c: Int): Boolean =
        (c in 'a'.code..'z'.code) ||
            (c in 'A'.code..'Z'.code) ||
            (c in '0'.code..'9'.code) ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
}
