package com.typebit.model

import kotlinx.serialization.Serializable

/** Lifecycle status of a torrent, mirroring typebit's `SessionStatus`. */
enum class TorrentStatus {
    /** Magnet link whose metadata is still being fetched. */
    FETCHING_METADATA,

    /** Actively downloading. */
    DOWNLOADING,

    /** All pieces verified; we are seeding (or done). */
    SEEDING,

    /** Paused by the user. */
    PAUSED,

    /** Stopped (files closed). */
    STOPPED,

    /** Terminal failure. */
    FAILED,
}

/**
 * Left-panel filter, modeled after qBittorrent's transfer-list filters.
 */
enum class TorrentFilter(val labelRes: String) {
    ALL("全部"),
    DOWNLOADING("下载中"),
    SEEDING("做种中"),
    COMPLETED("已完成"),
    RESUME("正在执行"),
    PAUSED("已暂停"),
    ACTIVE("活跃"),
    INACTIVE("不活跃"),
    STOPPED("已停止"),
    STOPPED_UPLOAD("停止上传"),
    STOPPED_DOWNLOAD("停止下载"),
    CHECKING("正在检查"),
    ERROR("出错"),
}

/** One file inside a torrent (paths are already decoded segments). */
data class FileEntry(
    val path: List<String>,
    val length: Long,
    /** User rename (relative path) — null when the original name is kept. */
    val renamed: String? = null,
) {
    val displayPath: String get() = path.joinToString("/")

    /** Name shown to the user: the rename wins over the original path. */
    val effectivePath: String get() = renamed ?: displayPath
}

/** A tracker (announce URL) and its last known state. */
data class TrackerInfo(
    val url: String,
    val tier: Int = 0,
    val status: TrackerStatus = TrackerStatus.UNKNOWN,
    val seeds: Int = 0,
    val leeches: Int = 0,
    val message: String = "",
) {
    val scheme: String get() = url.substringBefore("://").uppercase()
}

enum class TrackerStatus { UNKNOWN, WORKING, UPDATING, NOT_WORKING }

/** A peer snapshot (best-effort; typebit 0.1.0 exposes limited peer info). */
data class PeerInfo(
    val address: String = "",
    val client: String = "",
    val progress: Double = 0.0,
    val downSpeed: Long = 0,
    val upSpeed: Long = 0,
    val isSeed: Boolean = false,
)

/**
 * The display model the UI renders. Rebuilt from engine queries every poll
 * tick plus the add-time metadata mirror.
 */
data class Torrent(
    val hash: String,
    val name: String,
    val saveDir: String,
    val status: TorrentStatus,
    val sizeBytes: Long,
    val downloadedBytes: Long,
    /** typebit 0.1.0 does not expose per-torrent uploads — always 0. */
    val uploadedBytes: Long,
    val progress: Double,
    val pieceCount: Int,
    val havePieces: Int,
    val pieceLength: Long,
    val isPrivate: Boolean,
    val metadataReady: Boolean,
    val addedAt: Long,
    val createdAt: Long?,
    val createdBy: String?,
    val comment: String?,
    val kind: String,
    val trackers: List<TrackerInfo>,
    val files: List<FileEntry>,
    val seeds: Int,
    val peers: Int,
    val downSpeed: Long,
    val upSpeed: Long,
    val completedAt: Long?,
    val category: String,
    val tags: List<String>,
    /** Verified-piece bitfield (MSB-first), hex-encoded; empty when unknown. */
    val haveBitsHex: String = "",
    /** Per-file priorities (0=Skip, 1=Normal, 2=High); empty = all Normal. */
    val filePriorities: List<Int> = emptyList(),
) {
    val remainingBytes: Long get() = (sizeBytes - downloadedBytes).coerceAtLeast(0L)

    val isComplete: Boolean get() = progress >= 1.0 || status == TorrentStatus.SEEDING

    val ratio: Double
        get() = if (downloadedBytes > 0) uploadedBytes.toDouble() / downloadedBytes else 0.0

    /** Estimated seconds remaining, or null when not downloading. */
    val etaSeconds: Long?
        get() = when {
            status != TorrentStatus.DOWNLOADING -> null
            downSpeed <= 0 -> null
            remainingBytes <= 0 -> 0L
            else -> remainingBytes / downSpeed
        }

    fun matchesFilter(filter: TorrentFilter): Boolean = when (filter) {
        TorrentFilter.ALL -> true
        TorrentFilter.DOWNLOADING -> status == TorrentStatus.DOWNLOADING || status == TorrentStatus.FETCHING_METADATA
        TorrentFilter.SEEDING -> status == TorrentStatus.SEEDING
        TorrentFilter.COMPLETED -> isComplete
        TorrentFilter.RESUME -> status == TorrentStatus.DOWNLOADING || status == TorrentStatus.SEEDING || status == TorrentStatus.FETCHING_METADATA
        TorrentFilter.PAUSED -> status == TorrentStatus.PAUSED
        TorrentFilter.ACTIVE -> status == TorrentStatus.DOWNLOADING || status == TorrentStatus.SEEDING || status == TorrentStatus.FETCHING_METADATA
        TorrentFilter.INACTIVE -> status == TorrentStatus.PAUSED || status == TorrentStatus.STOPPED || status == TorrentStatus.FAILED
        TorrentFilter.STOPPED -> status == TorrentStatus.STOPPED
        TorrentFilter.STOPPED_UPLOAD -> status == TorrentStatus.STOPPED && isComplete
        TorrentFilter.STOPPED_DOWNLOAD -> status == TorrentStatus.STOPPED && !isComplete
        TorrentFilter.CHECKING -> false
        TorrentFilter.ERROR -> status == TorrentStatus.FAILED
    }
}

/**
 * A persisted torrent record (app-level metadata the engine cannot carry).
 * `data` holds either raw `.torrent` bytes (kind=FILE) or a magnet URI
 * (kind=MAGNET) so torrents survive app restarts.
 */
@Serializable
data class TorrentRecord(
    val hash: String,
    val name: String,
    val kind: String, // "FILE" | "MAGNET"
    val saveDir: String,
    val data: String, // base64 .torrent bytes or magnet URI
    val addedAt: Long,
    val paused: Boolean = false,
    val category: String = "",
    val tags: List<String> = emptyList(),
    /** Per-file priorities (0=Skip, 1=Normal, 2=High); empty = all Normal. */
    val filePriorities: List<Int> = emptyList(),
    /** Extra tracker URLs added at runtime (persisted across restarts). */
    val trackers: List<String> = emptyList(),
    /** Per-file renames: file index → new relative path (persisted). */
    val renames: Map<Int, String> = emptyMap(),
)
