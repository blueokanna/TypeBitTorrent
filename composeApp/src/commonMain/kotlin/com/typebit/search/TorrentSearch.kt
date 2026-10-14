package com.typebit.search

/** One auto-search result; only entries carrying a real magnet link are surfaced. */
data class TorrentSearchResult(
    val title: String,
    val magnet: String,
    val size: String = "",
    val seeds: Int = 0,
    val leeches: Int = 0,
    val date: String = "",
    val source: String,
    val detailUrl: String = "",
)

// Streaming / cloud-drive markers: titles pointing at "watch online" or cloud mirrors (百度网盘/云盘/在线播放…)
// are rejected, so the list never surfaces non-torrent content.
object OnlineVideoFilter {
    private val BLOCKED = listOf(
            "在线观看",
            "在线播放",
            "在线看",
            "在线视频",
            "在线点播",
            "免费在线",
            "云播",
            "网播",
            "百度网盘",
            "百度云",
            "网盘",
            "云盘",
            "阿里云盘",
            "夸克网盘",
            "迅雷云盘",
            "秒传",
            "在线下载",
            "高速播放",
            "免下载",
    )

    fun isOnlineVideo(title: String): Boolean {
        val t = title.trim().lowercase()
        if (t.isEmpty()) return true
        return BLOCKED.any { t.contains(it.lowercase()) }
    }
}
