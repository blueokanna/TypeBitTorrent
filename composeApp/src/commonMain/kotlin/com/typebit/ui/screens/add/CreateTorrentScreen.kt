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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.platform.rememberCreateTorrentPicker
import com.typebit.platform.rememberSaveTorrentPicker
import com.typebit.store.AppState
import com.typebit.store.AppStore
import com.typebit.ui.screens.settings.CompactDropdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Supported piece sizes; 128 MiB / 256 MiB are first-class options. */
private val PIECE_SIZES: List<Long> =
    listOf(
        16L * 1024,
        32L * 1024,
        64L * 1024,
        128L * 1024,
        256L * 1024,
        512L * 1024,
        1024L * 1024,
        2L * 1024 * 1024,
        4L * 1024 * 1024,
        8L * 1024 * 1024,
        16L * 1024 * 1024,
        32L * 1024 * 1024,
        64L * 1024 * 1024,
        128L * 1024 * 1024,
        256L * 1024 * 1024,
    )

private fun pieceLabel(b: Long): String =
    when {
        b >= 1024L * 1024 * 1024 -> "${b / (1024L * 1024 * 1024)} GiB"
        b >= 1024L * 1024 -> "${b / (1024L * 1024)} MiB"
        else -> "${b / 1024} KiB"
    }

/** 制作种子（v1 .torrent）对话框。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTorrentScreen(
    store: AppStore,
    onBack: () -> Unit,
) {
    var files by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pieceLength by remember { mutableStateOf(4L * 1024 * 1024) }
    var name by remember { mutableStateOf("") }
    var announce by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var doneBytes by remember { mutableStateOf<ByteArray?>(null) }
    var doneName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val pickFiles = rememberCreateTorrentPicker { picked ->
        files = picked
        if (name.isBlank() && picked.size == 1) {
            name = picked[0].second.substringBeforeLast('.').ifBlank { picked[0].second }
        }
        status = null
    }
    val savePicker = rememberSaveTorrentPicker(
        data = doneBytes ?: ByteArray(0),
        defaultName = doneName.ifBlank { "new.torrent" },
        onDone = { ok ->
            busy = false
            status = if (ok) "已保存 .torrent" else "保存失败或已取消"
        },
    )

    val create = create@{
        if (files.isEmpty()) {
            status = "请先选择要打包的文件"
            return@create
        }
        val effectiveName = name.trim().ifBlank { "torrent" }
        busy = true
        status = "正在计算分块校验…（大文件可能较慢）"
        scope.launch {
            val bytes =
                withContext(Dispatchers.Default) {
                    store.makeTorrent(
                        files = files,
                        pieceLength = pieceLength.toInt(),
                        name = effectiveName,
                        announce = announce.trim().ifBlank { null },
                        comment = comment.trim().ifBlank { null },
                    )
                }
            if (bytes == null || bytes.isEmpty()) {
                busy = false
                status = "制作失败：无法读取文件或参数无效"
            } else {
                doneBytes = bytes
                doneName = "$effectiveName.torrent"
                busy = false
                status = null
                savePicker()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("制作种子") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "文件",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = pickFiles, enabled = !busy) {
                        Icon(Icons.Default.Build, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("选择文件（可多选）")
                    }
                    Spacer(Modifier.height(8.dp))
                    files.forEach { (_, fname) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                fname,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { files = files.filter { it.second != fname } }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "移除",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    if (files.isEmpty()) {
                        Text(
                            "尚未选择文件（Android 上会先复制到应用缓存）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "参数",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("分块大小", style = MaterialTheme.typography.bodyMedium)
                        CompactDropdown(
                            options = PIECE_SIZES,
                            selected = pieceLength,
                            onSelect = { pieceLength = it },
                            labelOf = { pieceLabel(it) },
                        )
                        Text(
                            "越大，校验开销越低，适合大文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        placeholder = { Text("种子名（默认取自文件）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = announce,
                        onValueChange = { announce = it },
                        label = { Text("Tracker（可选）") },
                        placeholder = { Text("http://…/announce") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("注释（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(onClick = { create() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    Text("正在制作…")
                } else {
                    Text("创建并保存 .torrent")
                }
            }
            HorizontalDivider()
            Text(
                "生成的是 BEP-3 v1 种子；分块支持 16 KiB ~ 256 MiB（含 128/256 MiB）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
