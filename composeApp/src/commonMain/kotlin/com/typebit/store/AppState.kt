package com.typebit.store

import com.typebit.engine.LogEntryDto
import com.typebit.model.Torrent
import com.typebit.model.TorrentFilter
import com.typebit.data.AppSettings

/** Immutable; every mutation goes through [AppStore.dispatch]. */
data class AppState(
    val torrents: List<Torrent> = emptyList(),
    val selectedHash: String? = null,
    val filter: TorrentFilter = TorrentFilter.ALL,
    val searchQuery: String = "",
    val settings: AppSettings = AppSettings(),
    val engineRunning: Boolean = false,
    val peerId: String = "",
    val dhtNodes: Int = 0,
    /** Active (not failed) trackers across all torrents. */
    val trackerCount: Int = 0,
    /** NAT-detected external UDP endpoint (BEP-42); empty / 0 until confirmed. */
    val extIp: String = "",
    val extPort: Int = 0,
    /** UPnP/NAT-PMP mapping phase as reported by the engine (6 = mapped, 9 = failed). */
    val portMapPhase: Int = 0,
    val portMapPort: Int = 0,
    /** TCP listen port actually bound to the socket, not the mapped one. */
    val listenPort: Int = 0,
    /** LSD (BEP-14): multicast announces sent, BT-SEARCH datagrams received, peers found. */
    val lsdSent: Long = 0,
    val lsdRecv: Long = 0,
    val lsdPeers: Long = 0,
    /** Anti-leech: detections so far, and the most recent client names (deduped, capped). */
    val antiLeechCount: Int = 0,
    val antiLeechClients: List<String> = emptyList(),
    /** Global wire rates from the native host, bytes/sec. */
    val globalDownRate: Long = 0,
    val globalUpRate: Long = 0,
    val totalDownloaded: Long = 0,
    val totalUploaded: Long = 0,
    val recheckHash: String = "",
    val recheckRunning: Boolean = false,
    val recheckDone: Long = 0,
    val recheckTotal: Long = 0,
    val recheckMessage: String = "",
    val logs: List<LogEntryDto> = emptyList(),
    val categories: List<String> = listOf("未分类"),
    val tags: List<String> = emptyList(),
    val lastError: String? = null,
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
