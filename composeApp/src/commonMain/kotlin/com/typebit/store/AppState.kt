package com.typebit.store

import com.typebit.engine.LogEntryDto
import com.typebit.model.Torrent
import com.typebit.model.TorrentFilter
import com.typebit.data.AppSettings

/**
 * Immutable UI state. Everything the screens render flows from this single
 * object; mutations only happen through [AppStore.dispatch].
 */
data class AppState(
    val torrents: List<Torrent> = emptyList(),
    val selectedHash: String? = null,
    val filter: TorrentFilter = TorrentFilter.ALL,
    val searchQuery: String = "",
    val settings: AppSettings = AppSettings(),
    val engineRunning: Boolean = false,
    val peerId: String = "",
    val dhtNodes: Int = 0,
    /** Trackers currently active (not failed) across all torrents (live). */
    val trackerCount: Int = 0,
    /** Anti-leech: count of known leeching clients detected so far. */
    val antiLeechCount: Int = 0,
    /** Anti-leech: most recent detected client names (deduped, capped). */
    val antiLeechClients: List<String> = emptyList(),
    /** Wire-level global rates (bytes/sec), from the native host. */
    val globalDownRate: Long = 0,
    val globalUpRate: Long = 0,
    /** Cumulative wire bytes, for the "session totals" status row. */
    val totalDownloaded: Long = 0,
    val totalUploaded: Long = 0,
    val logs: List<LogEntryDto> = emptyList(),
    val categories: List<String> = listOf("未分类"),
    val tags: List<String> = emptyList(),
    val lastError: String? = null,
) {
    val selectedTorrent: Torrent? get() = torrents.firstOrNull { it.hash == selectedHash }

    val filteredTorrents: List<Torrent>
        get() {
            val q = searchQuery.trim()
            return torrents.filter { t ->
                t.matchesFilter(filter) &&
                    (q.isEmpty() || t.name.contains(q, ignoreCase = true) || t.hash.contains(q, ignoreCase = true))
            }
        }

    val aggregateDownRate: Long get() = torrents.sumOf { it.downSpeed }
    val aggregateUpRate: Long get() = torrents.sumOf { it.upSpeed }
}
