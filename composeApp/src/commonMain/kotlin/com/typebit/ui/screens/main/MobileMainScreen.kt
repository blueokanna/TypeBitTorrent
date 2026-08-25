@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")
// OverloadResolutionAmbiguity is an IDE false positive from Kotlin Multiplatform
// expect/actual resolution (PlatformBackHandler); both targets compile cleanly.

package com.typebit.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.app.Route
import com.typebit.model.Torrent
import com.typebit.platform.PlatformBackHandler
import com.typebit.store.AppState
import com.typebit.store.AppStore
import com.typebit.ui.components.EmptyState
import com.typebit.ui.components.StatusBadge
import com.typebit.ui.components.TorrentProgressBar
import com.typebit.ui.util.Format

/** Android layout: top bar + torrent card list + bottom navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileMainScreen(
    state: AppState,
    store: AppStore,
    onRoute: (Route) -> Unit,
) {
    var detailHash by remember { mutableStateOf<String?>(null) }

    // Android back gesture: while a detail page is open, back closes it
    // (one level up) instead of leaving the app. This inner handler wins
    // over the route-level one.
    PlatformBackHandler(enabled = detailHash != null) { detailHash = null }

    if (detailHash != null) {
        val t = state.torrents.firstOrNull { it.hash == detailHash }
        if (t != null) {
            TorrentDetailPage(torrent = t, store = store, onBack = { detailHash = null })
            return
        }
        detailHash = null
    }

    var gridMode by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("TypeBitTorrent", style = MaterialTheme.typography.titleLarge)
                        // Live status row: DHT nodes + active trackers + wire
                        // speeds, refreshed every poll tick. 8.dp below the
                        // title, 12.dp gaps keep 下载 / 上传 visually
                        // separated.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusPill("DHT ${state.dhtNodes}")
                            StatusPill("Tracker ${state.trackerCount}")
                            if (state.extIp.isNotEmpty()) {
                                StatusPill("外网 ${state.extIp}${if (state.extPort != 0) ":${state.extPort}" else ""}")
                            }
                            Text(
                                "↓ ${Format.speed(state.globalDownRate)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "↑ ${Format.speed(state.globalUpRate)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onRoute(Route.CREATE) }) {
                        Icon(Icons.Default.Build, contentDescription = "制作种子")
                    }
                    IconButton(onClick = { gridMode = !gridMode }) {
                        Icon(
                            if (gridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (gridMode) "列表视图" else "网格视图",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    label = { Text("种子") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { onRoute(Route.ADD) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    label = { Text("添加") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { onRoute(Route.SEARCH) },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("搜索") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { onRoute(Route.RSS) },
                    icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
                    label = { Text("RSS") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { onRoute(Route.SETTINGS) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.torrents.isEmpty()) {
                EmptyState(
                    title = "暂无种子",
                    subtitle = "点击底部「添加」导入 .torrent 或磁力链接",
                )
            } else if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(168.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.torrents, key = { it.hash }) { t ->
                        TorrentCard(torrent = t, onClick = { detailHash = t.hash }, grid = true)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.torrents, key = { it.hash }) { t ->
                        TorrentCard(torrent = t, onClick = { detailHash = t.hash })
                    }
                }
            }
        }
    }
}

/** A small rounded status pill (DHT / Tracker counts). */
@Composable
private fun StatusPill(text: String) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun TorrentCard(torrent: Torrent, onClick: () -> Unit, grid: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (grid) Modifier else Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    torrent.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusBadge(torrent.status)
            }
            Spacer(Modifier.height(8.dp))
            TorrentProgressBar(torrent.progress.toFloat(), torrent.status)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${Format.percent(torrent.progress)} · ${Format.bytes(torrent.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "↓ ${Format.speed(torrent.downSpeed)} · ↑ ${Format.speed(torrent.upSpeed)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Full-screen detail page for Android. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorrentDetailPage(
    torrent: Torrent,
    store: AppStore,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(torrent.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${Format.percent(torrent.progress)} · ${Format.eta(torrent.etaSeconds)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.Button(onClick = {
                    if (torrent.status == com.typebit.model.TorrentStatus.PAUSED) store.resume(torrent.hash) else store.pause(torrent.hash)
                }, Modifier.weight(1f)) {
                    Text(if (torrent.status == com.typebit.model.TorrentStatus.PAUSED) "继续" else "暂停")
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = { store.remove(torrent.hash); onBack() },
                    Modifier.weight(1f),
                ) {
                    Text("删除")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            com.typebit.ui.screens.detail.TorrentDetailContent(torrent = torrent, store = store)
        }
    }
}
