package com.typebit.engine

import kotlinx.serialization.Serializable

/**
 * Wire DTOs exchanged with the Rust engine. Field names intentionally mirror
 * the JSON produced by `native/src/` (snake_case) — no renaming magic, so the
 * contract stays greppable on both sides.
 */

/** A single file entry from the mirrored metainfo. */
@Serializable
data class FileDto(
    val path: List<String> = emptyList(),
    val length: Long = 0L,
) {
    val displayPath: String get() = path.joinToString("/")
}

/** The full mirrored metainfo for one torrent (null for unresolved magnets). */
@Serializable
data class TorrentInfoDto(
    val hash: String = "",
    val name: String = "",
    val kind: String = "unknown",
    val size: Long = 0L,
    val piece_length: Long = 0L,
    val piece_count: Long = 0L,
    val `private`: Boolean = false,
    val metadata_ready: Boolean = false,
    val comment: String? = null,
    val created_by: String? = null,
    val creation_date: Long? = null,
    val announce_list: List<List<String>> = emptyList(),
    val web_seeds: List<String> = emptyList(),
    val files: List<FileDto> = emptyList(),
) {
    val trackers: List<String> get() = announce_list.flatten()

    /** Single-file torrents get a clean name; multi-file use the top folder. */
    fun effectiveName(): String =
        if (name.isNotBlank()) name
        else files.firstOrNull()?.path?.firstOrNull() ?: hash
}

/** Per-torrent persisted state returned by `nativeTorrentStates`. */
@Serializable
data class TorrentStateDto(
    val hash: String = "",
    val save_path: String = "",
    val have: Long = 0L,
    /** Verified-piece bitfield, MSB-first, hex-encoded. */
    val hx: String = "",
    val paused: Boolean = false,
)

/**
 * One engine event. `t` selects the variant:
 * 1=PeerConnected(h,a,p) 2=PieceVerified(h,piece) 3=HashFailure(h,piece)
 * 4=TorrentComplete(h) 5=MetadataComplete(h) 6=MetadataFailed(h)
 * 7=TrackerAnnounced(h,peers) 8=DhtNodeCount(n)
 */
@Serializable
data class EngineEventDto(
    val t: Int = 0,
    val h: String = "",
    val a: String? = null,
    val p: String? = null,
    val piece: Int? = null,
    val peers: Int? = null,
    val n: Int? = null,
    /** Anti-leech: detected client name (t=9). */
    val c: String? = null,
)

/** A log line drained from the engine. */
@Serializable
data class LogEntryDto(
    val l: Int = 0,
    val m: String = "",
)

/** Global engine stats for the status bar. */
data class GlobalStats(
    val downloadedTotal: Long = 0L,
    val uploadedTotal: Long = 0L,
    val dhtNodes: Int = 0,
    val torrentCount: Int = 0,
)
