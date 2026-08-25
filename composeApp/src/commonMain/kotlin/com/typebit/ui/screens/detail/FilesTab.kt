package com.typebit.ui.screens.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.model.FileEntry
import com.typebit.model.Torrent
import com.typebit.store.AppStore
import com.typebit.ui.components.EmptyState
import com.typebit.ui.screens.settings.CompactDropdown
import com.typebit.ui.util.Format

/** 文件 tab — full file tree with per-file priority + rename control. */
@Composable
fun FilesTab(torrent: Torrent, store: AppStore, modifier: Modifier = Modifier) {
    if (torrent.files.isEmpty()) {
        EmptyState(
            title = "暂无文件列表",
            subtitle = if (torrent.metadataReady) "该引擎版本不暴露文件明细" else "磁力链接元数据尚未获取",
        )
        return
    }
    var renamingIndex by remember { mutableStateOf<Int?>(null) }
    val renamingFile = renamingIndex?.let { torrent.files.getOrNull(it) }

    if (renamingFile != null) {
        RenameFileDialog(
            file = renamingFile,
            onDismiss = { renamingIndex = null },
            onConfirm = { name ->
                store.renameFile(torrent.hash, renamingIndex!!, name)
                renamingIndex = null
            },
        )
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("路径", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("大小", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        itemsIndexed(torrent.files) { i, f ->
            FileRow(
                file = f,
                priority = torrent.filePriorities.getOrElse(i) { 1 },
                onPriority = { p -> store.setFilePriority(torrent.hash, i, p) },
                onRename = { renamingIndex = i },
            )
        }
    }
}

/** Priority labels shared with the add dialog (0=Skip, 1=Normal, 2=High). */
private val PRIORITY_LABELS = listOf("跳过", "普通", "高")

/** Rename dialog: the staged file keeps its original name while downloading,
 *  and is promoted to the new name on completion. */
@Composable
private fun RenameFileDialog(
    file: FileEntry,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(file) { mutableStateOf(file.effectivePath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名文件") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (file.renamed != null) "原路径：${file.displayPath}" else "下载期间仍写入原名 .part，完成后以新名称保存。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FileRow(
    file: FileEntry,
    priority: Int,
    onPriority: (Int) -> Unit,
    onRename: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (file.length > 0) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.effectivePath,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.renamed != null) {
                Text(
                    "原: ${file.displayPath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(Format.bytes(file.length), style = MaterialTheme.typography.bodySmall)
        IconButton(onClick = onRename) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "重命名",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        CompactDropdown(
            options = listOf(0, 1, 2),
            selected = priority.coerceIn(0, 2),
            onSelect = { onPriority(it) },
            labelOf = { PRIORITY_LABELS[it.coerceIn(0, 2)] },
        )
    }
}
