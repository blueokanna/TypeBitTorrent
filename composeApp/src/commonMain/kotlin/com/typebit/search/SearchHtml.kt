package com.typebit.search

/** HTML helpers shared by the search engines (regex-based, no DOM dep). */
object SearchHtml {
    /** Decode the common HTML entities (incl. numeric `&#39;`). */
    fun decode(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val amp = s.indexOf('&', i)
            if (amp < 0) {
                sb.append(s, i, s.length)
                break
            }
            sb.append(s, i, amp)
            val semi = s.indexOf(';', amp)
            if (semi < 0 || semi - amp > 10) {
                sb.append('&')
                i = amp + 1
                continue
            }
            val ent = s.substring(amp + 1, semi)
            when (ent) {
                "amp" -> sb.append('&')
                "lt" -> sb.append('<')
                "gt" -> sb.append('>')
                "quot" -> sb.append('"')
                "apos" -> sb.append('\'')
                "nbsp" -> sb.append(' ')
                "hellip" -> sb.append('…')
                "mdash" -> sb.append('—')
                "ndash" -> sb.append('–')
                "rsquo" -> sb.append('\'')
                "lsquo" -> sb.append('\'')
                "rdquo" -> sb.append('"')
                "ldquo" -> sb.append('"')
                else -> {
                    if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        val code = ent.substring(2).toIntOrNull(16)
                        if (code != null) sb.append(code.toChar()) else sb.append('&').append(ent).append(';')
                    } else if (ent.startsWith("#")) {
                        val code = ent.substring(1).toIntOrNull(10)
                        if (code != null) sb.append(code.toChar()) else sb.append('&').append(ent).append(';')
                    } else {
                        sb.append('&').append(ent).append(';')
                    }
                }
            }
            i = semi + 1
        }
        return sb.toString()
    }

    /** Remove all tags from a fragment, collapsing whitespace. */
    fun stripTags(s: String): String =
            decode(s.replace(Regex("""<[^>]*>"""), " "))
                    .replace(Regex("""\s+"""), " ")
                    .trim()

    /** Extract the text of a single tag (first match). */
    fun tagText(html: String, tag: String, attrs: String = ""): String {
        val re = Regex("""<$tag[^>]*$attrs[^>]*>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
        return re.find(html)?.groupValues?.get(1)?.let { stripTags(it) } ?: ""
    }

    /** First magnet URI in a fragment, or null. */
    fun magnetIn(html: String): String? =
            Regex("""magnet:\?xt=urn:btih:[0-9a-fA-F]{40}(?:[^"'\s<>]*)*""")
                    .find(html)
                    ?.value
                    ?.replace("&amp;", "&")

    /** Absolute URL resolution for relative hrefs. */
    fun absolute(base: String, href: String): String =
            when {
                href.startsWith("http://") || href.startsWith("https://") -> href
                href.startsWith("/") -> base.removeSuffix("/") + href
                else -> base.removeSuffix("/") + "/" + href
            }

    /** URL-encode a search query (space → %20, keep letters/digits). */
    fun urlEncode(query: String): String {
        val sb = StringBuilder(query.length + 8)
        for (c in query) {
            when {
                c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)
                c == ' ' -> sb.append("%20")
                else -> {
                    val bytes = c.toString().toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%')
                        sb.append("0123456789ABCDEF"[((b.toInt() ushr 4) and 0xF)])
                        sb.append("0123456789ABCDEF"[b.toInt() and 0xF])
                    }
                }
            }
        }
        return sb.toString()
    }
}
