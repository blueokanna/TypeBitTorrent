@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")
// OverloadResolutionAmbiguity is an IDE false positive from Kotlin Multiplatform
// expect/actual resolution (rememberTorrentFilePicker); both targets compile cleanly.

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

/**
 * qBittorrent-style add-torrent dialog: pick a `.torrent` (or paste a magnet), inspect the file
 * tree BEFORE adding (collapsible folders, tri-state selection, per-file priority), choose the
 * files/priorities, pick the save path / category / tags and start-or-pause.
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

    // Two-phase magnet state: the resolved hash, whether a resolve is in
    // flight, and the failure message when metadata never arrived.
    var magnetHash by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }

    var saveDir by remember { mutableStateOf(state.settings.downloads.defaultSavePath) }
    var category by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var startNow by remember { mutableStateOf(true) }

    // Selection/priority keyed by the FLAT file index (not displayPath), so a
    // directory toggle can address every descendant leaf in one call.
    val fileSel = remember { mutableStateMapOf<Int, Boolean>() }
    val priorities = remember { mutableStateMapOf<Int, Int>() }
    var filterText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Whenever a new torrent is previewed (file picked OR magnet resolved),
    // reset the selection map and initialize every file to SELECTED with
    // normal priority. Clearing first is essential: a second torrent must
    // not inherit stale indices/values from the previous preview.
    LaunchedEffect(preview) {
        fileSel.clear()
        priorities.clear()
        preview?.files?.forEachIndexed { i, _ ->
            fileSel[i] = true
            priorities[i] = 1
        }
    }

    // True when every preview file is selected (default = selected).
    val allSelected =
            preview?.files?.indices?.all { fileSel[it] ?: true } ?: true
    // Selected count with the same implicit-default semantics.
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

    // Clean up a pending magnet when the user leaves (cancel / back), so a
    // cancelled dialog never leaves a half-configured magnet running.
    fun leaveDialog() {
        magnetHash?.let { store.cancelMagnetPending(it) }
        onBack()
    }

    // Per-file priorities aligned with the file tree (0=Skip, 1=Normal,
    // 2=High); unchecked = Skip. Shared by the .torrent and magnet paths.
    fun buildPriorities(): List<Int> =
            preview?.files.orEmpty().mapIndexed { i, _ ->
                val sel = fileSel[i] ?: true
                val p = priorities[i] ?: 1
                when {
                    !sel -> 0        // unchecked = Skip
                    p == 2 -> 2      // High
                    else -> 1        // Normal
                }
            }

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
                            IconButton(onClick = { leaveDialog() }) {
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

            // Resolution status (magnet phase 1): spinner while the engine
            // fetches metadata via DHT / trackers / LAN LSD.
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
                            OutlinedTextField(
                                    value = filterText,
                                    onValueChange = { filterText = it },
                                    placeholder = { Text("筛选文件...") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            // 全选 / 取消全选 iterates the REAL file list (not
                            // the sparse map), so it works even before the user
                            // touched any checkbox.
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
                        // The file tree is a LazyColumn; it MUST sit in a
                        // bounded-height container. This screen's outer
                        // Column is `verticalScroll` — an unbounded (infinite)
                        // max height would make the LazyColumn crash with
                        // "measured with an infinity maximum height"
                        // (nesting LazyColumn inside a scrollable Column).
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
                                    filter = filterText,
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
                                // .torrent — single-phase add with the tree.
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
                                // Magnet — phase 2: commit the file selection.
                                store.commitMagnetSelection(
                                        magnetHash!!,
                                        buildPriorities(),
                                        paused = !startNow,
                                )
                                added = true
                            } else {
                                // Magnet — phase 1: resolve metadata first,
                                // then the file tree appears for selection.
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
                                    val info = store.waitMetadata(hash, 90_000)
                                    resolving = false
                                    if (info == null) {
                                        resolveError = "元数据获取超时（请确认网络可用、DHT/Tracker 可达）"
                                        return@launch
                                    }
                                    // LaunchedEffect(preview) resets and seeds
                                    // the selection map (all files selected).
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
