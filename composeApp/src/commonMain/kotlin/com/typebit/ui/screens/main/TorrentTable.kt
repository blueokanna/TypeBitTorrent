package com.typebit.ui.screens.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.typebit.ui.components.StatusBadge
import com.typebit.ui.components.TorrentProgressBar
import com.typebit.ui.theme.TypeBitThemeColors
import com.typebit.ui.util.Format

private val W_NAME = 420.dp
private val W_SIZE = 90.dp
private val W_PROGRESS = 160.dp
private val W_STATUS = 92.dp
private val W_SEEDS = 54.dp
private val W_PEERS = 54.dp
private val W_DOWN = 96.dp
private val W_UP = 96.dp
private val W_ETA = 76.dp
private val W_RATIO = 60.dp

/** Sortable column identity, mirroring qBittorrent's transfer table. */
private enum class SortKey { NAME, SIZE, PROGRESS, STATUS, SEEDS, PEERS, DOWN, UP, ETA, RATIO }

private data class SortState(val key: SortKey, val desc: Boolean = false)

/**
 * MD3-Expressive desktop transfer table: a large-radius surface card with a
 * hover/selection color animation, springy row appearance and click-to-sort
 * headers — the qBittorrent columns, presented the Material way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TorrentTable(
    torrents: List<Torrent>,
    selectedHash: String?,
    onSelect: (String) -> Unit,
) {
    var sort by remember { mutableStateOf(SortState(SortKey.NAME)) }
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

    Card(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            // Header row
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SortHeader("名称", W_NAME, SortKey.NAME, sort) { sort = it }
                SortHeader("大小", W_SIZE, SortKey.SIZE, sort) { sort = it }
                SortHeader("进度", W_PROGRESS, SortKey.PROGRESS, sort) { sort = it }
                SortHeader("状态", W_STATUS, SortKey.STATUS, sort) { sort = it }
                SortHeader("种子", W_SEEDS, SortKey.SEEDS, sort, alignEnd = true) { sort = it }
                SortHeader("下载者", W_PEERS, SortKey.PEERS, sort, alignEnd = true) { sort = it }
                SortHeader("↓ 速度", W_DOWN, SortKey.DOWN, sort, alignEnd = true) { sort = it }
                SortHeader("↑ 速度", W_UP, SortKey.UP, sort, alignEnd = true) { sort = it }
                SortHeader("剩余", W_ETA, SortKey.ETA, sort, alignEnd = true) { sort = it }
                SortHeader("分享率", W_RATIO, SortKey.RATIO, sort, alignEnd = true) { sort = it }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(sorted, key = { it.hash }) { t ->
                    TorrentRow(
                        torrent = t,
                        selected = t.hash == selectedHash,
                        onClick = { onSelect(t.hash) },
                    )
                }
            }
        }
    }
}

/** Clickable column header with a sort-direction indicator. */
@Composable
private fun SortHeader(
    title: String,
    width: Dp,
    key: SortKey,
    sort: SortState,
    alignEnd: Boolean = false,
    onSort: (SortState) -> Unit,
) {
    val active = sort.key == key
    Row(
        Modifier
            .width(width)
            .clip(MaterialTheme.shapes.medium)
            .clickable {
                onSort(if (active) SortState(key, !sort.desc) else SortState(key))
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
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
}

@Composable
private fun TorrentRow(
    torrent: Torrent,
    selected: Boolean,
    onClick: () -> Unit,
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
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name + inline progress
        Column(Modifier.width(W_NAME)) {
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
        Cell(Format.bytes(torrent.sizeBytes), W_SIZE)
        Box(Modifier.width(W_PROGRESS), contentAlignment = Alignment.CenterStart) {
            Text(
                Format.percent(torrent.progress),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(Modifier.width(W_STATUS), contentAlignment = Alignment.CenterStart) {
            StatusBadge(torrent.status)
        }
        Cell(Format.count(torrent.seeds), W_SEEDS, alignEnd = true)
        Cell(Format.count(torrent.peers), W_PEERS, alignEnd = true)
        Cell(Format.speed(torrent.downSpeed), W_DOWN, alignEnd = true, color = TypeBitThemeColors.status.download)
        Cell(Format.speed(torrent.upSpeed), W_UP, alignEnd = true, color = TypeBitThemeColors.status.seed)
        Cell(Format.eta(torrent.etaSeconds), W_ETA, alignEnd = true)
        Cell(Format.ratio(torrent.ratio), W_RATIO, alignEnd = true)
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
