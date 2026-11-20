@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")
// OverloadResolutionAmbiguity is an IDE false positive from Kotlin Multiplatform expect/actual

package com.typebit.ui.screens.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.typebit.ui.theme.TypeBitMotion

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.typebit.ui.components.GlassNavigationBar
import com.typebit.ui.components.AddTorrentFab
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.app.Route
import com.typebit.model.Torrent
import com.typebit.model.TorrentStatus
import com.typebit.platform.PlatformBackHandler
import com.typebit.platform.shareText
import com.typebit.store.AppState
import com.typebit.store.AppStore
import com.typebit.ui.components.DeleteTorrentDialog
import com.typebit.ui.components.EmptyState
import com.typebit.ui.components.RenameTorrentDialog
import com.typebit.ui.components.SpeedPair
import com.typebit.ui.components.StatsDialog
import com.typebit.ui.components.StatusBadge
import com.typebit.ui.components.TorrentActions
import com.typebit.ui.components.TorrentActionsSheet
import com.typebit.ui.components.TorrentProgressBar
import com.typebit.ui.screens.rss.RssScreen
import com.typebit.ui.screens.search.SearchScreen
import com.typebit.ui.screens.settings.SettingsScreen
import com.typebit.ui.theme.TypeBitThemeColors
import com.typebit.ui.util.Format

private enum class MobileSort(val label: String) {
    NAME("按名称"),
    AVAILABILITY("按可用性"),
    DOWN("按下载速度"),
    UP("按上传速度"),
    RATIO("按分享率");

    val comparator: Comparator<Torrent> by lazy {
        when (this) {
            NAME -> compareBy { it.name.lowercase() }
            AVAILABILITY -> compareBy<Torrent> { it.seeds }.thenBy { it.peers }
            DOWN -> compareBy { it.downSpeed }
            UP -> compareBy { it.upSpeed }
            RATIO -> compareBy { it.ratio }
        }
    }
}

/** Destinations in the persistent mobile navigation bar. */
enum class MobileSection(val label: String) {
    TORRENTS("种子"),
    SEARCH("搜索"),
    RSS("RSS"),
    SETTINGS("设置"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MobileMainScreen(
    state: AppState,
    store: AppStore,
    onRoute: (Route) -> Unit,
) {
    var detailHash by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf(MobileSection.TORRENTS) }

    // Back closes an open detail page (one level up) instead of leaving the app; this
    // handler wins over the route-level one. On a section, back returns to Torrents.
    PlatformBackHandler(enabled = detailHash != null) { detailHash = null }
    PlatformBackHandler(enabled = detailHash == null && section != MobileSection.TORRENTS) {
        section = MobileSection.TORRENTS
    }

    val selected = state.torrents.firstOrNull { it.hash == detailHash }
    LaunchedEffect(detailHash, selected?.hash, state.engineRunning) {
        if (state.engineRunning && selected == null) detailHash = null
    }
    val savedState = rememberSaveableStateHolder()
    SharedTransitionLayout {
        AnimatedContent(
            targetState = selected?.hash,
            transitionSpec = { TypeBitMotion.containerTransition(targetState != null) },
            label = "torrentDetail",
        ) { hash ->
            val page = this
            val bounds: @Composable (String) -> Modifier = { id ->
                Modifier.sharedBounds(rememberSharedContentState(id), page,
                    boundsTransform = { _, _ -> TypeBitMotion.scheme.defaultSpatialSpec() })
            }
            if (hash == null) {
                savedState.SaveableStateProvider("home") {
                    MobileSections(state, store, onRoute, section, { section = it }, { detailHash = it }, bounds)
                }
            } else {
                state.torrents.firstOrNull { it.hash == hash }?.let { torrent ->
                    TorrentDetailPage(torrent, store, { detailHash = null }, bounds(hash))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MobileSections(
    state: AppState,
    store: AppStore,
    onRoute: (Route) -> Unit,
    section: MobileSection,
    onSection: (MobileSection) -> Unit,
    onDetail: (String) -> Unit,
    bounds: @Composable (String) -> Modifier,
) {
    var gridMode by rememberSaveable { mutableStateOf(false) }
    val backdrop = rememberHazeState()
    var showStats by remember { mutableStateOf(false) }

    var sortKey by rememberSaveable { mutableStateOf(MobileSort.NAME) }
    var sortDesc by rememberSaveable { mutableStateOf(false) }

    var actionsFor by remember { mutableStateOf<Torrent?>(null) }
    var renameFor by remember { mutableStateOf<Torrent?>(null) }
    var deleteFor by remember { mutableStateOf<Torrent?>(null) }

    val sorted = remember(state.torrents, sortKey, sortDesc) {
        val list = state.torrents.sortedWith(sortKey.comparator)
        if (sortDesc) list.reversed() else list
    }

    Scaffold(
        floatingActionButton = {
            AnimatedVisibility(
                visible = section == MobileSection.TORRENTS,
                enter = fadeIn(TypeBitMotion.emphasized) + scaleIn(TypeBitMotion.expressive),
                exit = fadeOut(TypeBitMotion.emphasized) + scaleOut(TypeBitMotion.expressive),
            ) {
                AddTorrentFab(onClick = { onRoute(Route.ADD) })
            }
        },
        topBar = {
            // Only Torrents owns the global toolbar; other sections render their own top bar.
            if (section == MobileSection.TORRENTS) {
                TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "TypeBitTorrent",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showStats = true }) {
                        Icon(Icons.Default.BarChart, contentDescription = "统计")
                    }
                    IconButton(onClick = { onRoute(Route.CREATE) }) {
                        Icon(Icons.Default.Build, contentDescription = "制作种子")
                    }
                    IconButton(onClick = { gridMode = !gridMode }) {
                        Icon(
                            if (gridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (gridMode) "列表视图" else "网格视图",
                        )
                    }
                    var sortMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            MobileSort.entries.forEach { key ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            key.label,
                                            fontWeight = if (sortKey == key) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    trailingIcon = {
                                        if (sortKey == key) {
                                            Text(if (sortDesc) "↓" else "↑")
                                        }
                                    },
                                    onClick = {
                                        if (sortKey == key) sortDesc = !sortDesc
                                        else {
                                            sortKey = key
                                            sortDesc = false
                                        }
                                        sortMenu = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("取消排序") },
                                onClick = {
                                    sortKey = MobileSort.NAME
                                    sortDesc = false
                                    sortMenu = false
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
            }
        },
        bottomBar = {
            GlassNavigationBar(backdrop, state.settings.appearance) {
                MobileSection.entries.forEach { s ->
                    NavigationBarItem(
                        selected = section == s,
                        onClick = { onSection(s) },
                        icon = {
                            Icon(
                                when (s) {
                                    MobileSection.TORRENTS -> Icons.Default.Download
                                    MobileSection.SEARCH -> Icons.Default.Search
                                    MobileSection.RSS -> Icons.Default.RssFeed
                                    MobileSection.SETTINGS -> Icons.Default.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(s.label) },
                    )
                }
            }
        },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        val outer = PaddingValues(start = padding.calculateStartPadding(direction), top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(direction))
        AnimatedContent(section, Modifier.fillMaxSize().hazeSource(backdrop).padding(outer)
            .consumeWindowInsets(outer).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            transitionSpec = { TypeBitMotion.containerTransition(targetState.ordinal > initialState.ordinal) },
            label = "mobileSection",
        ) { active ->
            when (active) {
                MobileSection.SEARCH ->
                    SearchScreen(
                        listBottomPadding = padding.calculateBottomPadding(),
                        state = state,
                        store = store,
                        onBack = { onSection(MobileSection.TORRENTS) },
                        showBack = false,
                    )
                MobileSection.RSS ->
                    RssScreen(
                        listBottomPadding = padding.calculateBottomPadding(),
                        state = state,
                        store = store,
                        onBack = { onSection(MobileSection.TORRENTS) },
                        showBack = false,
                    )
                MobileSection.SETTINGS ->
                    SettingsScreen(
                        listBottomPadding = padding.calculateBottomPadding(),
                        state = state,
                        store = store,
                        onBack = { onSection(MobileSection.TORRENTS) },
                        showBack = false,
                    )
                MobileSection.TORRENTS -> {
                    if (state.torrents.isEmpty()) {
                        EmptyState(
                            title = "暂无种子",
                            subtitle = "通过「添加种子」导入 .torrent 或磁力链接",
                            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = if (gridMode) GridCells.Adaptive(176.dp) else GridCells.Fixed(1),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = if (gridMode) 12.dp else 0.dp,
                                end = if (gridMode) 12.dp else 0.dp, top = if (gridMode) 12.dp else 0.dp,
                                bottom = padding.calculateBottomPadding() + 88.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(if (gridMode) 12.dp else 0.dp),
                        ) {
                            items(sorted, key = { it.hash }) { t ->
                                val item = Modifier.animateItem(TypeBitMotion.emphasized,
                                    TypeBitMotion.scheme.defaultSpatialSpec(), TypeBitMotion.emphasized).then(bounds(t.hash))
                                val open = { onDetail(t.hash) }
                                val actions = { actionsFor = t }
                                if (gridMode) GridTorrentCard(t, item, open, actions)
                                else TorrentCard(t, item, open, actions)
                            }
                        }
                    }
                }
            }
        }
    }

    actionsFor?.let { t ->
        TorrentActionsSheet(
            torrent = t,
            actions =
                    TorrentActions(
                            onRename = {
                                actionsFor = null
                                renameFor = t
                            },
                            onShare = {
                                actionsFor = null
                                shareText(t.name, store.magnetLink(t.hash, t.name))
                            },
                            onTogglePause = {
                                actionsFor = null
                                if (t.status == TorrentStatus.PAUSED) store.resume(t.hash)
                                else store.pause(t.hash)
                            },
                            onDelete = {
                                actionsFor = null
                                deleteFor = t
                            },
                    ),
            onDismiss = { actionsFor = null },
        )
    }

    renameFor?.let { t ->
        RenameTorrentDialog(
            initial = t.name,
            onConfirm = { name ->
                store.renameTorrent(t.hash, name)
                renameFor = null
            },
            onDismiss = { renameFor = null },
        )
    }

    deleteFor?.let { t ->
        DeleteTorrentDialog(
            torrent = t,
            onConfirm = {
                store.remove(t.hash)
                deleteFor = null
            },
            onDismiss = { deleteFor = null },
        )
    }

    if (showStats) {
        StatsDialog(state, store) { showStats = false }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TorrentCard(
    torrent: Torrent,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
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
                    "${Format.percent(torrent.progress)} · ${Format.targetSize(torrent.selectedBytes, torrent.sizeBytes)} · Tracker ${torrent.trackers.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SpeedPair(down = torrent.downSpeed, up = torrent.upSpeed)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTorrentCard(
    torrent: Torrent,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .padding(4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                torrent.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StatusBadge(torrent.status)
            }
            Spacer(Modifier.height(10.dp))
            TorrentProgressBar(torrent.progress.toFloat(), torrent.status)
            Spacer(Modifier.height(6.dp))
            Text(
                "${Format.percent(torrent.progress)} · ${Format.targetSize(torrent.selectedBytes, torrent.sizeBytes)} · Tracker ${torrent.trackers.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "↓ ${Format.speed(torrent.downSpeed)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TypeBitThemeColors.status.download,
                )
                Text(
                    "↑ ${Format.speed(torrent.upSpeed)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TypeBitThemeColors.status.seed,
                )
            }
            if (torrent.status == TorrentStatus.SEEDING) {
                Text(
                    "分享率 ${Format.ratio(torrent.ratio)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorrentDetailPage(
    torrent: Torrent,
    store: AppStore,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Scaffold(
        modifier = modifier,
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
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).padding(12.dp),
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
