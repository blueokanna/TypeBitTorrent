package com.typebit.ui.screens.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.store.AppStore
import kotlinx.coroutines.launch

private enum class DetailTab(val label: String) {
    INFO("信息"),
    FILES("文件"),
    TRACKERS("Tracker"),
    PEERS("Peers"),
    PIECES("分块"),
    RECEIPTS("回执"),
}

/**
 * Tabbed torrent detail. Used as the fixed-height bottom panel on desktop
 * and as a full-screen page on mobile.
 *
 * MD3E navigation: the six labels live in a **scrollable** tab row (they
 * scroll horizontally on narrow phones instead of being squeezed into
 * half-width pills), and the content is a **pager** — swipe left/right to
 * flip tabs, tap a label to jump. The tab row is the selected-page
 * indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDetailContent(
    torrent: Torrent,
    store: AppStore,
    modifier: Modifier = Modifier,
) {
    val tabs = DetailTab.entries
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0) { tabs.size }

    Column(modifier.fillMaxSize()) {
        SecondaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            edgePadding = 4.dp,
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(
                    selected = pagerState.currentPage == i,
                    onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                    text = { Text(t.label) },
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (tabs[page]) {
                DetailTab.INFO -> InfoTab(torrent)
                DetailTab.FILES -> FilesTab(torrent, store)
                DetailTab.TRACKERS -> TrackersTab(torrent, store)
                DetailTab.PEERS -> PeersTab(torrent, store)
                DetailTab.PIECES -> PiecesTab(torrent)
                DetailTab.RECEIPTS -> ReceiptsTab(torrent, store)
            }
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
