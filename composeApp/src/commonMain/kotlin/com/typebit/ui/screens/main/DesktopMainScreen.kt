package com.typebit.ui.screens.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typebit.app.Route
import com.typebit.model.TorrentFilter
import com.typebit.store.AppState
import com.typebit.store.AppStore
import com.typebit.ui.components.EmptyState
import com.typebit.ui.components.SpeedPair
import com.typebit.ui.screens.detail.DetailPanel
import com.typebit.ui.util.Format

/**
 * MD3-Expressive desktop layout: a permanent sidebar (brand + filters +
 * categories with leading icons), a surfaceContainer top bar with a
 * large-radius search field and FilledTonal actions, a large-radius
 * transfer-table card, a springy detail panel and a slim status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopMainScreen(
    state: AppState,
    store: AppStore,
    onRoute: (Route) -> Unit,
) {
    PermanentNavigationDrawer(
        drawerContent = {
            Sidebar(state = state, store = store)
        },
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(state = state, store = store, onRoute = onRoute)

            TorrentToolbar(state = state, store = store)

            // Content switches between empty state and the table card.
            AnimatedContent(
                targetState = state.torrents.isEmpty(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "mainContent",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { empty ->
                if (empty) {
                    EmptyState(
                        title = "暂无种子",
                        subtitle = "点击右上角 + 添加 .torrent 文件或磁力链接",
                        action = {
                            FilledTonalIconButton(onClick = { onRoute(Route.ADD) }) {
                                Icon(Icons.Default.Add, contentDescription = "添加种子")
                            }
                        },
                    )
                } else {
                    TorrentTable(
                        torrents = state.filteredTorrents,
                        selectedHash = state.selectedHash,
                        onSelect = store::select,
                    )
                }
            }

            // Bottom detail panel for the selected torrent (springy slide-in).
            AnimatedVisibility(
                visible = state.selectedTorrent != null,
                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.selectedTorrent?.let { t ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        DetailPanel(torrent = t, store = store)
                    }
                }
            }

            StatusBar(state = state, store = store)
        }
    }
}

/** Brand + filter/category navigation sidebar. */
@Composable
private fun Sidebar(state: AppState, store: AppStore) {
    Column(Modifier.fillMaxHeight().width(264.dp).padding(12.dp)) {
        // Brand header
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "TypeBit",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "BitTorrent 客户端",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        GroupLabel("筛选")
        TorrentFilter.entries.forEach { filter ->
            val count = state.torrents.count { it.matchesFilter(filter) }
            NavigationDrawerItem(
                label = { Text("${filter.labelRes}") },
                selected = state.filter == filter,
                onClick = { store.setFilter(filter) },
                icon = { Icon(filterIcon(filter), contentDescription = null) },
                badge = { CountBadge(count) },
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        GroupLabel("分类")
        LazyColumn(Modifier.weight(1f)) {
            items(state.categories, key = { it }) { cat ->
                val count = if (cat == "未分类") {
                    state.torrents.count { it.category.isBlank() || it.category == "未分类" }
                } else {
                    state.torrents.count { it.category == cat }
                }
                NavigationDrawerItem(
                    label = { Text(cat) },
                    selected = false,
                    onClick = {
                        store.setSearch(if (cat == "未分类") "" else "分类:$cat")
                    },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    badge = { CountBadge(count) },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }

        // Engine status mini-card at the bottom.
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.engineRunning) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.engineRunning) "引擎运行中" else "引擎未启动",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun CountBadge(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun filterIcon(f: TorrentFilter): ImageVector = when (f) {
    TorrentFilter.ALL -> Icons.Default.AllInbox
    TorrentFilter.DOWNLOADING -> Icons.Default.ArrowDownward
    TorrentFilter.SEEDING -> Icons.Default.ArrowUpward
    TorrentFilter.COMPLETED -> Icons.Default.CheckCircle
    TorrentFilter.RESUME -> Icons.Default.PlayCircle
    TorrentFilter.PAUSED -> Icons.Default.PauseCircle
    TorrentFilter.ACTIVE -> Icons.Default.Bolt
    TorrentFilter.INACTIVE -> Icons.Default.RemoveCircle
    TorrentFilter.STOPPED -> Icons.Default.Stop
    TorrentFilter.STOPPED_UPLOAD -> Icons.Default.StopCircle
    TorrentFilter.STOPPED_DOWNLOAD -> Icons.Default.StopCircle
    TorrentFilter.CHECKING -> Icons.Default.Sync
    TorrentFilter.ERROR -> Icons.Default.ErrorOutline
}

/** Surface-container top bar: title + live speeds + tonal actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    state: AppState,
    store: AppStore,
    onRoute: (Route) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.filter.labelRes,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${state.filteredTorrents.size} 个种子",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SpeedPair(
                down = state.globalDownRate,
                up = state.globalUpRate,
                modifier = Modifier.padding(end = 12.dp),
            )
            IconButton(onClick = { onRoute(Route.SEARCH) }) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
            IconButton(onClick = { onRoute(Route.SETTINGS) }) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
            Spacer(Modifier.width(4.dp))
            FilledIconButton(onClick = { onRoute(Route.ADD) }) {
                Icon(Icons.Default.Add, contentDescription = "添加种子")
            }
        }
    }
}

/** Toolbar row: large-radius search field + bulk actions. */
@Composable
private fun TorrentToolbar(state: AppState, store: AppStore) {
    var query by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                store.setSearch(it)
            },
            placeholder = { Text("搜索名称或哈希…") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(onClick = {
            state.torrents.filter { it.status.name != "SEEDING" && it.status.name != "PAUSED" }
                .forEach { store.start(it.hash) }
        }) {
            Icon(Icons.Default.PlayArrow, contentDescription = "全部开始")
        }
        FilledTonalIconButton(onClick = {
            state.torrents.forEach { store.pause(it.hash) }
        }) {
            Icon(Icons.Default.Pause, contentDescription = "全部暂停")
        }
        FilledTonalIconButton(onClick = {
            state.selectedHash?.let { store.remove(it) }
        }) {
            Icon(Icons.Default.Delete, contentDescription = "删除所选")
        }
    }
}

/** Slim bottom status bar: totals + DHT + engine state. */
@Composable
private fun StatusBar(state: AppState, store: AppStore) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "已选 ${if (state.selectedHash != null) 1 else 0} / 共 ${state.torrents.size}",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("下载 ${Format.speed(state.globalDownRate)}", style = MaterialTheme.typography.labelMedium)
                Text("上传 ${Format.speed(state.globalUpRate)}", style = MaterialTheme.typography.labelMedium)
                Text("DHT ${state.dhtNodes} 节点", style = MaterialTheme.typography.labelMedium)
                if (state.lastError != null) {
                    Text(
                        state.lastError.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    IconButton(onClick = store::clearError, modifier = Modifier.size(18.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
