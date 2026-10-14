@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")
// OverloadResolutionAmbiguity is an IDE false positive from Kotlin Multiplatform expect/actual

package com.typebit.ui.screens.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.engine.TorrentInfoDto
import com.typebit.platform.Platform
import com.typebit.platform.rememberTorrentFilePicker
import com.typebit.store.AppState
import com.typebit.store.AppStore
import com.typebit.ui.components.FileTreeView
import com.typebit.ui.components.TreeLeaf
import com.typebit.ui.components.buildFileTree
import com.typebit.ui.components.findNodeByKey
import com.typebit.ui.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Add-torrent dialog: .torrent or magnet, with pre-add file-tree inspection and per-file priority. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTorrentScreen(
        state: AppState,
        store: AppStore,
        onBack: () -> Unit,
        showBack: Boolean = true,
) {
    var magnet by remember { mutableStateOf("") }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<TorrentInfoDto?>(null) }
    var added by remember { mutableStateOf(false) }

    // Two-phase magnet state: resolved hash, in-flight flag, failure message.
    var magnetHash by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }

    var saveDir by remember { mutableStateOf(state.settings.downloads.defaultSavePath) }
    var category by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var startNow by remember { mutableStateOf(true) }

    // Keyed by FLAT file index (not displayPath), so a dir toggle can address every leaf.
    val fileSel = remember { mutableStateMapOf<Int, Boolean>() }
    val priorities = remember { mutableStateMapOf<Int, Int>() }
    val scope = rememberCoroutineScope()

    // A new preview resets the maps: a second torrent must not inherit stale indices/values.
    LaunchedEffect(preview) {
        fileSel.clear()
        priorities.clear()
        preview?.files?.forEachIndexed { i, _ ->
            fileSel[i] = true
            priorities[i] = 1
        }
    }

    val allSelected =
            preview?.files?.indices?.all { fileSel[it] ?: true } ?: true
    val selectedCount = preview?.files?.indices?.count { fileSel[it] ?: true } ?: 0

    val pickTorrent = rememberTorrentFilePicker { bytes, name ->
        pendingBytes = bytes
        pendingName = name
        // A picked .torrent supersedes any in-flight magnet resolution.
        magnetHash?.let { store.cancelMagnetPending(it) }
        magnetHash = null
        resolveError = null
        scope.launch {
            val parsed = withContext(Dispatchers.Default) { store.parseTorrentFile(bytes) }
            preview = parsed
        }
    }

    val canAdd = pendingBytes != null || magnetHash != null || magnet.isNotBlank()

    // A started magnet resolve is ALREADY a persisted task: leaving the dialog must not delete
    // it (metadata may just be slow on a cold DHT); only an explicit cancel clears it.
    fun leaveDialog() {
        magnetHash?.let { store.releaseMagnetPending(it) }
        onBack()
    }

    fun buildPriorities(): List<Int> =
            preview?.files.orEmpty().mapIndexed { i, _ ->
                val sel = fileSel[i] ?: true
                val p = priorities[i] ?: 1
                when {
                    !sel -> 0
                    p == 2 -> 2
                    else -> 1
                }
            }

    LaunchedEffect(added) {
        if (added) onBack()
    }

    Scaffold(
            topBar = {
                TopAppBar(
                        title = { Text("添加种子") },
                        navigationIcon = {
                            if (showBack) {
                                IconButton(onClick = { leaveDialog() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
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

            if (resolving) {
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                ) {
                    Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                                modifier = Modifier.width(22.dp).height(22.dp),
                                strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                                "正在获取元数据（DHT / Tracker / 局域网 LSD）…",
                                style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            resolveError?.let { err ->
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                ) {
                    Text(
                            err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp),
                    )
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
                                    "文件（$selectedCount / ${p.files.size}）",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Select-all iterates the REAL file list, so it works before any checkbox touch.
                            OutlinedButton(
                                    onClick = {
                                        val newState = !allSelected
                                        preview?.files?.indices?.forEach { fileSel[it] = newState }
                                    }
                            ) { Text(if (allSelected) "取消全选" else "全选") }
                        }
                        Spacer(Modifier.height(4.dp))
                        val fileTree = remember(p.files) {
                            buildFileTree(
                                    p.files.mapIndexed { i, f ->
                                        TreeLeaf(i, f.path, f.length)
                                    }
                            )
                        }
                        // The file tree is a LazyColumn and must sit in a bounded-height container:
                        // inside this screen's verticalScroll Column an unbounded max height makes
                        // it crash ("measured with an infinity maximum height").
                        Box(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                            FileTreeView(
                                    roots = fileTree,
                                    isSelected = { fileSel[it] ?: true },
                                    priority = { priorities[it] ?: 1 },
                                    onToggleLeaf = { i, sel -> fileSel[i] = sel },
                                    onToggleDir = { dirKey, sel ->
                                        findNodeByKey(fileTree, dirKey)
                                                ?.leafIndices
                                                ?.forEach { fileSel[it] = sel }
                                    },
                                    onPriorityLeaf = { i, prio -> priorities[i] = prio },
                                    onPriorityDir = { dirKey, prio ->
                                        findNodeByKey(fileTree, dirKey)
                                                ?.leafIndices
                                                ?.forEach { priorities[it] = prio }
                                    },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                                "勾选 = 下载，未勾选 = 跳过；优先级影响分块调度（typebit 支持按文件选择）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

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
                            description = "磁力需先解析元数据；关闭则添加后立即暂停（可选文件后暂停）",
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
                            if (pendingBytes != null) {
                                val tags =
                                        tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                val effSave = saveDir.ifBlank { Platform.defaultDownloadDir() }
                                store.addTorrentFileEx(
                                        pendingBytes!!,
                                        pendingName,
                                        effSave,
                                        category,
                                        tags,
                                        !startNow,
                                        buildPriorities(),
                                )
                                added = true
                            } else if (magnetHash != null) {
                                store.commitMagnetSelection(
                                        magnetHash!!,
                                        buildPriorities(),
                                        paused = !startNow,
                                )
                                added = true
                            } else {
                                scope.launch {
                                    resolving = true
                                    resolveError = null
                                    val effSave = saveDir.ifBlank { Platform.defaultDownloadDir() }
                                    val hash = store.addMagnetResolve(magnet, effSave)
                                    if (hash == null) {
                                        resolving = false
                                        return@launch
                                    }
                                    magnetHash = hash
                                    // 180 s window: a cold-start DHT (empty routing table) and
                                    // unreachable public trackers make metadata take 60-120 s.
                                    val info = store.waitMetadata(hash, 180_000)
                                    resolving = false
                                    if (info == null) {
                                        // The task is already added and still resolving — keep it.
                                        store.releaseMagnetPending(hash)
                                        resolveError =
                                                "元数据获取超时，任务已保留并继续后台解析（首次启动 DHT 冷启动较慢）"
                                        return@launch
                                    }
                                    preview = info
                                }
                            }
                        },
                        enabled = canAdd && !resolving,
                        modifier = Modifier.weight(1f),
                ) {
                    if (resolving) {
                        CircularProgressIndicator(
                                modifier = Modifier.width(18.dp).height(18.dp),
                                strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                                if (pendingBytes == null && magnetHash == null)
                                        Icons.Default.Downloading
                                else Icons.Default.Link,
                                contentDescription = null,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                            when {
                                resolving -> "解析中…"
                                pendingBytes != null -> "添加"
                                magnetHash != null -> "添加"
                                else -> "解析并选择文件"
                            }
                    )
                }
                OutlinedButton(onClick = { leaveDialog() }, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
