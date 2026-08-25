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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.model.TrackerInfo
import com.typebit.model.TrackerStatus
import com.typebit.store.AppStore
import com.typebit.ui.components.EmptyState

/** Tracker tab — announce URLs with runtime add/remove (typebit 0.1.1). */
@Composable
fun TrackersTab(torrent: Torrent, store: AppStore, modifier: Modifier = Modifier) {
    var newUrl by remember { mutableStateOf("") }
    val trackers = torrent.trackers

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newUrl,
                onValueChange = { newUrl = it },
                label = { Text("添加 Tracker URL") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = newUrl.isNotBlank() && newUrl.contains("://"),
                onClick = {
                    store.addTracker(torrent.hash, newUrl.trim())
                    newUrl = ""
                },
            ) { Text("添加") }
        }

        if (trackers.isEmpty()) {
            EmptyState(
                title = "无 Tracker",
                subtitle = "该种子未声明 announce，将使用默认 Tracker 列表",
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("地址", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("协议", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            items(trackers, key = { it.url }) { t ->
                TrackerRow(
                    tracker = t,
                    onRemove = { store.removeTracker(torrent.hash, t.url) },
                )
            }
        }
    }
}

@Composable
private fun TrackerRow(tracker: TrackerInfo, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Public,
            contentDescription = null,
            tint = when (tracker.status) {
                TrackerStatus.WORKING -> MaterialTheme.colorScheme.primary
                TrackerStatus.NOT_WORKING -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            tracker.url,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(tracker.scheme, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "移除 Tracker", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
