package com.typebit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.model.TorrentStatus
import com.typebit.ui.theme.TypeBitThemeColors
import com.typebit.ui.util.Format

/**
 * Material 3 torrent grid (list/grid toggle target on desktop + Android).
 * Adaptive columns keep cards readable across phone, tablet and desktop.
 */
@Composable
fun TorrentGrid(
    torrents: List<Torrent>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(240.dp),
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(torrents, key = { it.hash }) { t ->
            GridTorrentCard(torrent = t, onClick = { onSelect(t.hash) })
        }
    }
}

/** A single MD3 grid card: name, status, progress, sizes and wire rates. */
@Composable
private fun GridTorrentCard(torrent: Torrent, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    torrent.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusBadge(torrent.status)
            }
            Spacer(Modifier.height(10.dp))
            TorrentProgressBar(torrent.progress.toFloat(), torrent.status)
            Spacer(Modifier.height(6.dp))
            Text(
                "${Format.percent(torrent.progress)} · ${Format.bytes(torrent.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${Format.bytes(torrent.downloadedBytes)} / ${Format.bytes(torrent.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    "做种完成 · 分享率 ${Format.ratio(torrent.ratio)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
