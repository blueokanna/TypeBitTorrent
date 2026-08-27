package com.typebit.ui.screens.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.ui.components.HorizontalTableScrollbar
import com.typebit.ui.components.StatusBadge
import com.typebit.ui.components.TorrentProgressBar
import com.typebit.ui.theme.TypeBitThemeColors
import com.typebit.ui.util.Format

/** Column identity + display metadata (label, width, alignment). */
private enum class SortKey(val label: String, val width: Dp, val alignEnd: Boolean) {
    NAME("名称", 420.dp, false),
    SIZE("大小", 90.dp, false),
    PROGRESS("进度", 160.dp, false),
    STATUS("状态", 92.dp, false),
    SEEDS("种子", 54.dp, true),
    PEERS("Tracker 下载者", 72.dp, true),
    DOWN("↓ 速度", 96.dp, true),
    UP("↑ 速度", 96.dp, true),
    ETA("剩余", 76.dp, true),
    RATIO("分享率", 60.dp, true),
}

private data class SortState(val key: SortKey, val desc: Boolean = false)

/**
 * MD3-Expressive desktop transfer table: a large-radius surface card with a
 * hover/selection color animation, springy row appearance and click-to-sort
 * headers. The table scrolls HORIZONTALLY when the window is too narrow
 * (columns are never clipped), and RIGHT-CLICKING a column header opens a
 * menu to hide / restore columns.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TorrentTable(
    torrents: List<Torrent>,
    selectedHash: String?,
    onSelect: (String) -> Unit,
    onActions: ((Torrent) -> Unit)? = null,
) {
    var sort by remember { mutableStateOf(SortState(SortKey.NAME)) }
    var hidden by remember { mutableStateOf(setOf<SortKey>()) }
    val visible = remember(hidden) { SortKey.entries.filter { it !in hidden } }
    val totalWidth = remember(visible) { visible.fold(0.dp) { acc, k -> acc + k.width } }

    val sorted = remember(torrents, sort) {
        val desc = sort.desc
        val list = torrents.sortedWith(
            when (sort.key) {
                SortKey.NAME -> compareBy { it.name.lowercase() }
                SortKey.SIZE -> compareBy { it.sizeBytes }
                SortKey.PROGRESS -> compareBy { it.progress }
                SortKey.STATUS -> compareBy { it.status.name }
                SortKey.SEEDS -> compareBy { it.seeds }
                SortKey.PEERS -> compareBy { it.peers }
                SortKey.DOWN -> compareBy { it.downSpeed }
                SortKey.UP -> compareBy { it.upSpeed }
                SortKey.ETA -> compareBy { it.etaSeconds }
                SortKey.RATIO -> compareBy { it.ratio }
            },
        )
        if (desc) list.reversed() else list
    }

    val toggleHidden: (SortKey) -> Unit = { key ->
        hidden = if (key in hidden) hidden - key else hidden + key
    }

    val hState = rememberScrollState()

    Card(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Horizontal scroll via drag on the visible scrollbar below
                // (and Shift+wheel, built into CMP desktop scrollables).
                .horizontalScroll(hState),
        ) {
            Column(Modifier.width(totalWidth)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    visible.forEach { key ->
                        SortHeader(
                            key = key,
                            sort = sort,
                            hidden = hidden,
                            onSort = { sort = it },
                            onToggleHidden = toggleHidden,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(sorted, key = { it.hash }) { t ->
                        TorrentRow(
                            torrent = t,
                            selected = t.hash == selectedHash,
                            onClick = { onSelect(t.hash) },
                            onActions = onActions,
                            visible = visible,
                        )
                    }
                }
            }
        }
        // A visible horizontal scrollbar on desktop (drag or shift+wheel).
        HorizontalTableScrollbar(
            hState = hState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        }
    }
}

/** Clickable column header with a sort indicator and a right-click menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SortHeader(
    key: SortKey,
    sort: SortState,
    hidden: Set<SortKey>,
    onSort: (SortState) -> Unit,
    onToggleHidden: (SortKey) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val active = sort.key == key
    Box {
        Row(
            Modifier
                .width(key.width)
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                    onClick = {
                        onSort(if (active) SortState(key, !sort.desc) else SortState(key))
                    },
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (key.alignEnd) Arrangement.End else Arrangement.Start,
        ) {
            Text(
                key.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (key.alignEnd) TextAlign.End else TextAlign.Start,
                maxLines = 1,
            )
            if (active) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    if (sort.desc) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.width(12.dp).height(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            SortKey.entries.forEach { k ->
                DropdownMenuItem(
                    text = { Text(k.label, maxLines = 1) },
                    leadingIcon = {
                        Icon(
                            if (k !in hidden) Icons.Filled.Check else Icons.Filled.VisibilityOff,
                            contentDescription = null,
                            modifier = Modifier.width(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { onToggleHidden(k) },
                )
            }
        }
    }
}

@Composable
private fun TorrentRow(
    torrent: Torrent,
    selected: Boolean,
    onClick: () -> Unit,
    onActions: ((Torrent) -> Unit)?,
    visible: List<SortKey>,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        animationSpec = com.typebit.ui.theme.TypeBitMotion.color,
        label = "rowBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(bg)
            .hoverable(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onActions?.let { { it(torrent) } },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { key ->
            when (key) {
                SortKey.NAME -> NameCell(torrent, key.width)
                SortKey.SIZE -> Cell(Format.bytes(torrent.sizeBytes), key.width)
                SortKey.PROGRESS -> PercentCell(torrent, key.width)
                SortKey.STATUS -> StatusCell(torrent, key.width)
                SortKey.SEEDS -> Cell(Format.count(torrent.seeds), key.width, alignEnd = true)
                SortKey.PEERS -> Cell(Format.count(torrent.peers), key.width, alignEnd = true)
                SortKey.DOWN -> Cell(
                    Format.speed(torrent.downSpeed), key.width, alignEnd = true,
                    color = TypeBitThemeColors.status.download,
                )
                SortKey.UP -> Cell(
                    Format.speed(torrent.upSpeed), key.width, alignEnd = true,
                    color = TypeBitThemeColors.status.seed,
                )
                SortKey.ETA -> Cell(Format.eta(torrent.etaSeconds), key.width, alignEnd = true)
                SortKey.RATIO -> Cell(Format.ratio(torrent.ratio), key.width, alignEnd = true)
            }
        }
    }
}

@Composable
private fun NameCell(torrent: Torrent, width: Dp) {
    Column(Modifier.width(width)) {
        Text(
            torrent.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        TorrentProgressBar(progress = torrent.progress.toFloat(), status = torrent.status)
        Spacer(Modifier.height(4.dp))
        Text(
            "${Format.percent(torrent.progress)} · ${torrent.havePieces}/${torrent.pieceCount} 分块",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PercentCell(torrent: Torrent, width: Dp) {
    Box(Modifier.width(width), contentAlignment = Alignment.CenterStart) {
        Text(
            Format.percent(torrent.progress),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatusCell(torrent: Torrent, width: Dp) {
    Box(Modifier.width(width), contentAlignment = Alignment.CenterStart) {
        StatusBadge(torrent.status)
    }
}

@Composable
private fun Cell(
    text: String,
    width: Dp,
    alignEnd: Boolean = false,
    color: Color? = null,
) {
    Box(
        Modifier.width(width).padding(end = 8.dp),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
