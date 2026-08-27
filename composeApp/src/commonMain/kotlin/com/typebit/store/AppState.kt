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
    /** NAT-detected external UDP IP (BEP-42), empty until confirmed. */
    val extIp: String = "",
    /** NAT-detected external UDP port (BEP-42), 0 until confirmed. */
    val extPort: Int = 0,
    /**
     * UPnP/NAT-PMP port-mapping phase (6 = mapped, 9 = failed). Live from
     * both the batched snapshot and the t=12 engine events.
     */
    val portMapPhase: Int = 0,
    /** External port granted by the gateway, 0 until mapped. */
    val portMapPort: Int = 0,
    /** Actual bound TCP listen port (0 = not listening yet). */
    val listenPort: Int = 0,
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
    /**
     * Result of the last Windows firewall/ICS action (desktop only):
     * `null` = nothing attempted yet. `true`/`false` = ok/failed.
     */
    val systemOk: Boolean? = null,
    val systemMessage: String = "",
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
