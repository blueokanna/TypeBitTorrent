package com.typebit.ui.screens.add

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.engine.TorrentInfoDto
import com.typebit.platform.Platform
import com.typebit.platform.rememberTorrentFilePicker
import com.typebit.store.AppState
import com.typebit.store.AppStore
import com.typebit.ui.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Per-file download priority shown in the add dialog. */
private enum class FilePriority(val label: String, val value: Int) {
    NORMAL("普通", 0),
    HIGH("高", 1),
    LOW("低", 2),
    SKIP("跳过", 3),
}

/**
 * qBittorrent-style add-torrent dialog: pick a `.torrent` (or paste a magnet), inspect the file
 * table BEFORE adding, choose the files/priorities, pick the save path / category / tags and
 * start-or-pause.
 *
 * Honesty note: `typebit 0.1.0`'s `add_torrent` has no per-file filter, so the selections below are
 * recorded on the torrent entry (visible in the detail panel) while the engine downloads every file
 * — documented in README.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTorrentScreen(
        state: AppState,
        store: AppStore,
        onBack: () -> Unit,
) {
    var magnet by remember { mutableStateOf("") }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<TorrentInfoDto?>(null) }
    var added by remember { mutableStateOf(false) }

    var saveDir by remember { mutableStateOf(state.settings.downloads.defaultSavePath) }
    var category by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var startNow by remember { mutableStateOf(true) }

    val fileSel = remember { mutableStateMapOf<String, Boolean>() }
    val priorities = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()

    val pickTorrent = rememberTorrentFilePicker { bytes, name ->
        pendingBytes = bytes
        pendingName = name
        scope.launch {
            val parsed = withContext(Dispatchers.Default) { store.parseTorrentFile(bytes) }
            preview = parsed
            parsed?.files?.forEach {
                fileSel[it.displayPath] = true
                priorities[it.displayPath] = 0
            }
        }
    }

    val canAdd = pendingBytes != null || magnet.isNotBlank()

    // After a successful add, leave the dialog and return to the main list.
    // No "已添加" pause step — adding should feel one-shot on both platforms.
    LaunchedEffect(added) {
        if (added) onBack()
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
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                            "磁力链接",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                            value = magnet,
                            onValueChange = { magnet = it },
                            placeholder = { Text("magnet:?xt=urn:btih:…") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                                "或",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { pickTorrent() }) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (pendingName.isBlank()) "选择 .torrent 文件" else pendingName)
                        }
                    }
                }
            }

            preview?.let { p ->
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor =
                                                MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                                "种子信息",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                                p.effectiveName(),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                        )
                        Text(
                                "大小 ${Format.bytes(p.size)} · ${p.files.size} 个文件 · 哈希 ${p.hash.take(16)}…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                    "文件（${fileSel.values.count { it }} / ${p.files.size}）",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                    onClick = {
                                        val all = fileSel.values.all { it }
                                        fileSel.keys.forEach { fileSel[it] = !all }
                                    }
                            ) { Text(if (fileSel.values.all { it }) "取消全选" else "全选") }
                        }
                        Spacer(Modifier.height(4.dp))
                        p.files.forEach { f ->
                            FilePickRow(
                                    path = f.displayPath,
                                    size = f.length,
                                    selected = fileSel[f.displayPath] ?: true,
                                    priority = priorities[f.displayPath] ?: 0,
                                    onToggle = { fileSel[f.displayPath] = it },
                                    onPriority = { priorities[f.displayPath] = it },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                                "勾选 = 下载，未勾选 = 跳过；高/低优先级影响分块调度（typebit 0.1.1 支持按文件选择）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // -- destination / metadata ---------------------------------------
            Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                            CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                            "保存与分类",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                            value = saveDir,
                            onValueChange = { saveDir = it },
                            label = { Text("保存到") },
                            placeholder = { Text(Platform.defaultDownloadDir()) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    com.typebit.ui.screens.settings.SettingDropdown(
                            label = "分类",
                            options = state.categories,
                            selected = category.ifBlank { state.categories.firstOrNull() ?: "未分类" },
                            onSelect = { category = it },
                            labelOf = { it },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                            value = tagsText,
                            onValueChange = { tagsText = it },
                            label = { Text("标签（逗号分隔）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    com.typebit.ui.screens.settings.SettingSwitch(
                            label = "添加后开始下载",
                            description = "关闭则以暂停状态添加",
                            checked = startNow,
                            onCheckedChange = { startNow = it },
                    )
                }
            }

            Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                        onClick = {
                            val tags =
                                    tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            val effSave = saveDir.ifBlank { Platform.defaultDownloadDir() }
                            // Per-file priorities aligned with the preview's
                            // file table (0=Skip, 1=Normal, 2=High) — the
                            // typebit 0.1.1 engine honors them (selective
                            // download). Unchecked files are skipped.
                            val filePriorities = preview?.files.orEmpty().map { f ->
                                val sel = fileSel[f.displayPath] ?: true
                                val p = priorities[f.displayPath] ?: 0
                                when {
                                    !sel || p == 3 -> 0        // unchecked / SKIP
                                    p == 1 -> 2                // HIGH
                                    else -> 1                  // NORMAL / LOW
                                }
                            }
                            if (pendingBytes != null) {
                                store.addTorrentFileEx(
                                        pendingBytes!!,
                                        pendingName,
                                        effSave,
                                        category,
                                        tags,
                                        !startNow,
                                        filePriorities
                                )
                            } else {
                                store.addMagnetEx(magnet, effSave, category, tags, !startNow)
                            }
                            added = true
                        },
                        enabled = canAdd,
                        modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("添加")
                }
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取消") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One file row in the add dialog: checkbox + name + size + priority menu. */
@Composable
private fun FilePickRow(
        path: String,
        size: Long,
        selected: Boolean,
        priority: Int,
        onToggle: (Boolean) -> Unit,
        onPriority: (Int) -> Unit,
) {
    Row(
            Modifier.fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onToggle(!selected) }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = onToggle)
        Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            Text(
                    Format.bytes(size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        com.typebit.ui.screens.settings.CompactDropdown(
                options = FilePriority.entries.toList(),
                selected = FilePriority.entries.first { it.value == priority },
                onSelect = { onPriority(it.value) },
                labelOf = { it.label },
        )
    }
}
