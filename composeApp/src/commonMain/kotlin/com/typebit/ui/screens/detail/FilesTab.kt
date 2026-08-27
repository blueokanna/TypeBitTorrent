package com.typebit.ui.screens.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.typebit.model.FileEntry
import com.typebit.model.Torrent
import com.typebit.store.AppStore
import com.typebit.ui.components.EmptyState
import com.typebit.ui.components.FileTreeView
import com.typebit.ui.components.TreeLeaf
import com.typebit.ui.components.buildFileTree
import com.typebit.ui.components.findNodeByKey

/** 文件 tab — qBittorrent 式文件树：折叠目录、每文件优先级 + 重命名。 */
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
    var filterText by remember { mutableStateOf("") }
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

    val fileTree = remember(torrent.files) {
        buildFileTree(torrent.files.mapIndexed { i, f -> TreeLeaf(i, f.path, f.length) })
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "路径",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = { Text("筛选文件...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "大小",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(72.dp))
        }
        HorizontalDivider()
        FileTreeView(
            roots = fileTree,
            isSelected = { true },
            priority = { torrent.filePriorities.getOrElse(it) { 1 }.coerceIn(0, 2) },
            onToggleLeaf = { _, _ -> },
            onToggleDir = { _, _ -> },
            onPriorityLeaf = { i, p -> store.setFilePriority(torrent.hash, i, p) },
            onPriorityDir = { dirKey, p ->
                findNodeByKey(fileTree, dirKey)?.leafIndices
                    ?.forEach { store.setFilePriority(torrent.hash, it, p) }
            },
            onRename = { renamingIndex = it },
            filter = filterText,
            showSelection = false,
        )
    }
}

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

