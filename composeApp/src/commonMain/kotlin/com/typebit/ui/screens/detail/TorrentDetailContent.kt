package com.typebit.ui.screens.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.store.AppStore

private enum class DetailTab(val label: String) {
    INFO("信息"),
    FILES("文件"),
    TRACKERS("Tracker"),
    PEERS("Peers"),
    PIECES("分块"),
}

/**
 * Tabbed torrent detail. Used as the fixed-height bottom panel on desktop
 * and as a full-screen page on mobile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDetailContent(
    torrent: Torrent,
    store: AppStore,
    modifier: Modifier = Modifier,
) {
    var tab by remember(torrent.hash) { mutableIntStateOf(0) }
    val tabs = DetailTab.entries

    Column(modifier.fillMaxSize()) {
        SecondaryTabRow(
            selectedTabIndex = tab,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            divider = {},
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(t.label) },
                )
            }
        }
        when (tabs[tab]) {
            DetailTab.INFO -> InfoTab(torrent)
            DetailTab.FILES -> FilesTab(torrent, store)
            DetailTab.TRACKERS -> TrackersTab(torrent, store)
            DetailTab.PEERS -> PeersTab(torrent)
            DetailTab.PIECES -> PiecesTab(torrent)
        }
    }
}

/** Desktop bottom panel wrapper. */
@Composable
fun DetailPanel(
    torrent: Torrent,
    store: AppStore,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(top = 4.dp),
    ) {
        TorrentDetailContent(torrent = torrent, store = store)
    }
}
