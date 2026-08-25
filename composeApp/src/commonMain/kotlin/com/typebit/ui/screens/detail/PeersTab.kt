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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.engine.PeerDto
import com.typebit.model.Torrent
import com.typebit.store.AppStore
import com.typebit.ui.components.EmptyState
import com.typebit.ui.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Peers tab — shows the LIVE peer swarm straight from the engine (address,
 * fingerprint, phase, rates), refreshed every 2 s. No fabricated rows.
 */
@Composable
fun PeersTab(torrent: Torrent, store: AppStore, modifier: Modifier = Modifier) {
    var peers by remember(torrent.hash) { mutableStateOf<List<PeerDto>>(emptyList()) }
    LaunchedEffect(torrent.hash) {
        while (true) {
            peers = withContext(Dispatchers.Default) { store.peers(torrent.hash) }
            delay(2_000)
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "当前连接：${peers.size}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(0.dp))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (peers.isEmpty()) {
            EmptyState(
                title = "暂无连接的 Peers",
                subtitle = "tracker / DHT 发现 peer 并完成握手后会显示在这里",
            )
        } else {
            LazyColumn {
                items(peers, key = { it.addr }) { p ->
                    PeerRow(p)
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerDto) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            tint = if (peer.phase == 2) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer.addr,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${peer.client} · ${Format.speed(peer.down)} ↓ / ${Format.speed(peer.up)} ↑",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            peer.phaseLabel,
            style = MaterialTheme.typography.bodySmall,
            color = when (peer.phase) {
                2 -> MaterialTheme.colorScheme.primary
                0, 1 -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            },
        )
    }
}
