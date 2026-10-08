package com.typebit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.model.TorrentStatus
import com.typebit.ui.i18n.LocalStrings
import com.typebit.ui.i18n.TextKey
import com.typebit.ui.util.Format

/**
 * Per-torrent actions shared by mobile (long-press sheet) and desktop
 * (right-click menu): rename / share (magnet) / pause-resume / delete.
 */

/** The action verbs every caller wires to the store. */
data class TorrentActions(
    val onRename: () -> Unit,
    val onShare: () -> Unit,
    val onTogglePause: () -> Unit,
    val onDelete: () -> Unit,
)

/** Common action list body (used by the sheet and the dropdown menu). */
@Composable
fun TorrentActionsList(
    torrent: Torrent,
    actions: TorrentActions,
) {
    val strings = LocalStrings.current
    ListItem(
        headlineContent = { Text(strings[TextKey.ACTION_RENAME]) },
        leadingContent = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
        modifier = Modifier.clickable(onClick = actions.onRename),
    )
    ListItem(
        headlineContent = { Text(strings[TextKey.ACTION_SHARE_MAGNET]) },
        leadingContent = { Icon(Icons.Default.Share, contentDescription = null) },
        modifier = Modifier.clickable(onClick = actions.onShare),
    )
    ListItem(
        headlineContent = {
            Text(
                if (torrent.status == TorrentStatus.PAUSED) {
                    strings[TextKey.ACTION_RESUME]
                } else {
                    strings[TextKey.ACTION_PAUSE]
                },
            )
        },
        leadingContent = {
            Icon(
                if (torrent.status == TorrentStatus.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = null,
            )
        },
        modifier = Modifier.clickable(onClick = actions.onTogglePause),
    )
    ListItem(
        headlineContent = { Text(strings[TextKey.ACTION_DELETE], color = MaterialTheme.colorScheme.error) },
        leadingContent = {
            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
        modifier = Modifier.clickable(onClick = actions.onDelete),
    )
}

/** Mobile-style bottom sheet for long-press actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentActionsSheet(
    torrent: Torrent,
    actions: TorrentActions,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                torrent.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Text(
                "${Format.percent(torrent.progress)} · ${Format.bytes(torrent.sizeBytes)} · " +
                    strings[TextKey.TORRENT_ACTION_SUMMARY]
                        .replace("{seeds}", torrent.seeds.toString())
                        .replace("{peers}", torrent.peers.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            TorrentActionsList(torrent = torrent, actions = actions)
        }
    }
}

/** Rename-torrent dialog (display name only). */
@Composable
fun RenameTorrentDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings[TextKey.RENAME_TORRENT]) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(strings[TextKey.NEW_TORRENT_NAME]) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.trim().isNotEmpty()) onConfirm(text.trim()) },
                enabled = text.trim().isNotEmpty(),
            ) { Text(strings[TextKey.CONFIRM]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings[TextKey.CANCEL]) }
        },
    )
}

/** Delete-confirmation dialog. */
@Composable
fun DeleteTorrentDialog(
    torrent: Torrent,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings[TextKey.DELETE_TORRENT]) },
        text = {
            Text(
                strings[TextKey.DELETE_TORRENT_CONFIRMATION]
                    .replace("{name}", torrent.name),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(strings[TextKey.ACTION_DELETE]) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings[TextKey.CANCEL]) }
        },
    )
}
