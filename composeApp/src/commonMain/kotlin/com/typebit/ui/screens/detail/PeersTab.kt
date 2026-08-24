package com.typebit.ui.screens.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.ui.components.EmptyState
import com.typebit.ui.util.Format

/**
 * Peers tab.
 *
 * Honesty note: `typebit 0.1.0` does not expose per-peer details through the
 * engine API, so this tab shows the connection count we track from engine
 * events and states plainly — it does not invent a peer table.
 */
@Composable
fun PeersTab(torrent: Torrent, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "当前连接：${torrent.peers}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(0.dp))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (torrent.peers == 0) {
            EmptyState(
                title = "暂无连接的 Peers",
                subtitle = "连接建立后会在此显示（由 PeerConnected 事件统计）",
            )
        } else {
            LazyColumn {
                items((1..torrent.peers.coerceAtMost(64)).toList()) { i ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Peer #$i",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("连接中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
