package com.typebit.ui.screens.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.model.FileEntry
import com.typebit.model.Torrent
import com.typebit.store.AppStore
import com.typebit.ui.components.EmptyState
import com.typebit.ui.screens.settings.SettingDropdown
import com.typebit.ui.util.Format

/** 文件 tab — full file tree with per-file download priority control. */
@Composable
fun FilesTab(torrent: Torrent, store: AppStore, modifier: Modifier = Modifier) {
    if (torrent.files.isEmpty()) {
        EmptyState(
            title = "暂无文件列表",
            subtitle = if (torrent.metadataReady) "该引擎版本不暴露文件明细" else "磁力链接元数据尚未获取",
        )
        return
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
            )
        }
    }
}

/** Priority labels shared with the add dialog (0=Skip, 1=Normal, 2=High). */
private val PRIORITY_LABELS = listOf("跳过", "普通", "高")

@Composable
private fun FileRow(
    file: FileEntry,
    priority: Int,
    onPriority: (Int) -> Unit,
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
        Text(
            file.displayPath,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(Format.bytes(file.length), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        SettingDropdown(
            label = "",
            options = listOf(0, 1, 2),
            selected = priority.coerceIn(0, 2),
            onSelect = { onPriority(it) },
            labelOf = { PRIORITY_LABELS[it.coerceIn(0, 2)] },
        )
    }
}
