package com.typebit.ui.screens.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typebit.platform.Platform
import com.typebit.platform.rememberTorrentFilePicker
import com.typebit.store.AppState
import com.typebit.store.AppStore

/**
 * Add-torrent screen: paste a magnet link and/or pick a `.torrent` file.
 * Desktop reaches this from the toolbar; Android from the bottom nav.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTorrentScreen(
    state: AppState,
    store: AppStore,
    onBack: () -> Unit,
) {
    var magnet by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(false) }

    val pickTorrent = rememberTorrentFilePicker { bytes, name ->
        store.addTorrentFile(bytes, name)
        picked = name
        added = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加种子") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "磁力链接",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = magnet,
                onValueChange = { magnet = it },
                placeholder = { Text("magnet:?xt=urn:btih:…") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    store.addMagnet(magnet)
                    added = true
                },
                enabled = magnet.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Link, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加磁力链接")
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("或", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { pickTorrent() }) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择 .torrent 文件")
                }
            }

            picked?.let {
                Text("已添加：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (added) {
                Text(
                    "完成！返回列表查看。保存目录：${state.settings.downloads.defaultSavePath.ifBlank { Platform.defaultDownloadDir() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.lastError != null) {
                Text(state.lastError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "提示：可粘贴任意 `magnet:`、`ed2k:`、`thunder://`、`qqdl://` 链接（TypeBit 引擎统一解析）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
