package com.typebit.webui

import kotlinx.serialization.Serializable

/**
 * Wire contract of the built-in WebUI (`/api/…` endpoints).
 *
 * Every field has a default so an older/newer SPA never fails to decode a
 * response; the server is the single place that maps [com.typebit.model.Torrent]
 * and engine state onto these shapes. This is what makes the same client core
 * usable from a browser on fnOS / Unraid / any headless box.
 */

@Serializable
data class LoginRequest(
    val username: String = "",
    val password: String = "",
)

@Serializable
data class SessionDto(
    val authenticated: Boolean = false,
    /** True when the server has no password configured yet. */
    val passwordRequired: Boolean = false,
    /** True when this client may act without logging in (loopback bypass). */
    val localBypass: Boolean = false,
    val username: String = "",
    val version: String = "",
    val platform: String = "",
    /** Unix seconds until the current session expires (0 = none). */
    val expiresAt: Long = 0L,
)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
    val message: String = "",
)

@Serializable
data class TorrentDto(
    val hash: String = "",
    val name: String = "",
    val status: String = "",
    val progress: Double = 0.0,
    val sizeBytes: Long = 0L,
    val selectedBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val downSpeed: Long = 0L,
    val upSpeed: Long = 0L,
    val etaSeconds: Long = -1L,
    val ratio: Double = 0.0,
    val seeds: Int = 0,
    val peers: Int = 0,
    val pieceCount: Int = 0,
    val havePieces: Int = 0,
    val trackerCount: Int = 0,
    val saveDir: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val addedAt: Long = 0L,
    val isComplete: Boolean = false,
    val metadataReady: Boolean = false,
)

@Serializable
data class StateDto(
    val engineRunning: Boolean = false,
    val platform: String = "",
    val peerId: String = "",
    val dhtNodes: Int = 0,
    /** Trackers currently announcing successfully across all torrents. */
    val activeTrackers: Int = 0,
    val listenPort: Int = 0,
    val extIp: String = "",
    val extPort: Int = 0,
    val portMapPhase: Int = 0,
    val portMapPort: Int = 0,
    val lsdSent: Long = 0L,
    val lsdRecv: Long = 0L,
    val lsdPeers: Long = 0L,
    val downRate: Long = 0L,
    val upRate: Long = 0L,
    val totalDownloaded: Long = 0L,
    val totalUploaded: Long = 0L,
    val antiLeechCount: Int = 0,
    val antiLeechClients: List<String> = emptyList(),
    val lastError: String = "",
    val torrents: List<TorrentDto> = emptyList(),
)

@Serializable
data class FileDto(
    val index: Int = 0,
    val path: String = "",
    val length: Long = 0L,
    val priority: Int = 1,
)

@Serializable
data class PeerRowDto(
    val addr: String = "",
    val client: String = "",
    val cc: String = "",
    val phase: Int = 0,
    val seed: Boolean = false,
    val down: Long = 0L,
    val up: Long = 0L,
    val inflight: Int = 0,
)

@Serializable
data class TrackerRowDto(
    val url: String = "",
    val status: String = "",
    val seeds: Int = 0,
    val leeches: Int = 0,
    val message: String = "",
)

@Serializable
data class DetailDto(
    val hash: String = "",
    val name: String = "",
    val saveDir: String = "",
    val kind: String = "",
    val sizeBytes: Long = 0L,
    val pieceLength: Long = 0L,
    val pieceCount: Int = 0,
    val havePieces: Int = 0,
    val haveBitsHex: String = "",
    val isPrivate: Boolean = false,
    val comment: String = "",
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val files: List<FileDto> = emptyList(),
    val peers: List<PeerRowDto> = emptyList(),
    val trackers: List<TrackerRowDto> = emptyList(),
    val receipts: List<ReceiptDto> = emptyList(),
)

@Serializable
data class ReceiptDto(
    val name: String = "",
    val path: String = "",
    val bytes: Long = 0L,
)

@Serializable
data class AddRequest(
    val magnet: String = "",
    /** Base64 of a `.torrent` file (the SPA uploads it inline — no multipart). */
    val torrentBase64: String = "",
    val fileName: String = "",
    val savePath: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val paused: Boolean = false,
    val name: String = "",
)

@Serializable
data class ActionRequest(
    val hash: String = "",
    /** `pause` | `resume` | `remove` */
    val action: String = "",
)

@Serializable
data class PriorityRequest(
    val hash: String = "",
    /** Flat file index → priority (0=Skip, 1=Normal, 2=High). */
    val priorities: Map<String, Int> = emptyMap(),
)

@Serializable
data class RenameRequest(
    val hash: String = "",
    val name: String = "",
    /** -1 = rename the torrent itself, otherwise the file index. */
    val fileIndex: Int = -1,
)

@Serializable
data class TrackerRequest(
    val hash: String = "",
    val add: String = "",
    val remove: String = "",
)

@Serializable
data class MakeTorrentPath(
    val abs: String = "",
    val rel: List<String> = emptyList(),
)

@Serializable
data class MakeTorrentRequest(
    /** Files to hash: absolute server paths + their relative path inside the torrent. */
    val files: List<MakeTorrentPath> = emptyList(),
    /** Convenience: a directory on the server; walked recursively. */
    val directory: String = "",
    val name: String = "",
    val pieceLength: Long = 0L,
    val trackers: List<String> = emptyList(),
    val comment: String = "",
    val source: String = "",
    val isPrivate: Boolean = false,
)

@Serializable
data class TorrentBytesDto(
    val ok: Boolean = false,
    val message: String = "",
    val base64: String = "",
    val name: String = "",
    val hash: String = "",
    val sizeBytes: Long = 0L,
    val pieceCount: Long = 0L,
    val pieceLength: Long = 0L,
    val fileCount: Int = 0,
)

@Serializable
data class MakeProgressDto(
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val running: Boolean = false,
    val cancelled: Boolean = false,
)

@Serializable
data class LogLineDto(
    val seq: Int = 0,
    val level: String = "",
    val message: String = "",
)

@Serializable
data class SearchResultDto(
    val title: String = "",
    val magnet: String = "",
    val sizeText: String = "",
    val seeds: Int = 0,
    val leeches: Int = 0,
    val source: String = "",
)

@Serializable
data class SearchResponseDto(
    val ok: Boolean = true,
    val message: String = "",
    val engines: List<String> = emptyList(),
    val results: List<SearchResultDto> = emptyList(),
)

@Serializable
data class RssFeedDto(
    val url: String = "",
    val title: String = "",
    val items: Int = 0,
    val error: String = "",
)

@Serializable
data class RssItemDto(
    val feed: String = "",
    val title: String = "",
    val link: String = "",
    val magnet: String = "",
    val pubDate: String = "",
)

@Serializable
data class RssRequest(
    val action: String = "",
    val url: String = "",
)

/** WebUI password change; the plaintext never touches the settings store. */
@Serializable
data class PasswordRequest(
    val password: String = "",
)
