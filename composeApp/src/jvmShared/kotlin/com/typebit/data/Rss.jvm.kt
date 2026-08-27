package com.typebit.data

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

actual fun fetchRssFeed(url: String, timeoutMs: Long): RssFeed? {
    return try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs.toInt()
        conn.readTimeout = timeoutMs.toInt()
        conn.setRequestProperty("User-Agent", "TypeBit/0.1 (+https://github.com/blueokanna/TypeBitTorrent)")
        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }
        val body = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        parseRss(body, url)
    } catch (_: Throwable) {
        null
    }
}

private fun parseRss(body: ByteArray, url: String): RssFeed? {
    val factory = DocumentBuilderFactory.newInstance().apply {
        // Defensive XML: no external entities.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(body))
    val root = doc.documentElement
    val items = ArrayList<RssItem>()

    fun textOf(node: org.w3c.dom.Node, tag: String): String {
        val list = node.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child.nodeName.equals(tag, ignoreCase = true) && child.firstChild != null) {
                return child.firstChild.nodeValue?.trim().orEmpty()
            }
        }
        return ""
    }

    // Atom <link rel="alternate" href="…"/> is self-closing (no text node),
    // so textOf() returns "" — the link must be read from the href attribute.
    fun attrOf(node: org.w3c.dom.Node, tag: String, attr: String): String {
        val list = node.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child.nodeName.equals(tag, ignoreCase = true) && child.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val el = child as org.w3c.dom.Element
                val href = el.getAttribute(attr)
                if (href.isNotBlank()) return href.trim()
            }
        }
        return ""
    }

    // RSS 2.0: <channel><item>…; Atom: <feed><entry>…
    if (root.nodeName.equals("rss", ignoreCase = true)) {
        val channel = root.childNodes.let { nl ->
            for (i in 0 until nl.length) if (nl.item(i).nodeName.equals("channel", ignoreCase = true)) return@let nl.item(i)
            null
        } ?: return RssFeed(url = url)
        val title = textOf(channel, "title")
        val nodeList = channel.childNodes
        for (i in 0 until nodeList.length) {
            val n = nodeList.item(i)
            if (n.nodeName.equals("item", ignoreCase = true)) {
                items.add(
                    RssItem(
                        title = textOf(n, "title"),
                        link = textOf(n, "link"),
                        description = textOf(n, "description"),
                        pubDate = textOf(n, "pubDate"),
                    ),
                )
            }
        }
        return RssFeed(url = url, title = title, items = items)
    } else if (root.nodeName.equals("feed", ignoreCase = true)) {
        val title = textOf(root, "title")
        val nodeList = root.childNodes
        for (i in 0 until nodeList.length) {
            val n = nodeList.item(i)
            if (n.nodeName.equals("entry", ignoreCase = true)) {
                items.add(
                    RssItem(
                        title = textOf(n, "title"),
                        link = textOf(n, "link").ifEmpty { attrOf(n, "link", "href") }.ifEmpty { textOf(n, "id") },
                        description = textOf(n, "summary").ifEmpty { textOf(n, "content") },
                        pubDate = textOf(n, "updated"),
                    ),
                )
            }
        }
        return RssFeed(url = url, title = title, items = items)
    }
    return null
}
