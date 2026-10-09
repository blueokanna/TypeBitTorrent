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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.platform.CreateTorrentInput
import com.typebit.platform.FileIO
import com.typebit.platform.rememberCreateTorrentPicker
import com.typebit.platform.rememberSaveTorrentPicker
import com.typebit.store.AppStore
import com.typebit.ui.screens.settings.CompactDropdown
import com.typebit.ui.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** One `label: value` line in the creation-result card. */
@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Auto piece-size band: 16 KiB .. 16 MiB keeps the piece count sane. */
private const val PIECE_AUTO_MIN = 16L * 1024
private const val PIECE_AUTO_MAX = 16L * 1024 * 1024

/**
 * Piece length that keeps a torrent's piece count around ~2 000 — the
 * qBittorrent-class default. Clamped to 16 KiB..16 MiB so we never emit a
 * million-piece torrent (unusable) or a 256 MiB-piece torrent for a 40 MiB
 * file (equally unusable).
 */
private fun recommendPieceLength(totalBytes: Long): Long {
    if (totalBytes <= 0L) return 256L * 1024
    var size = PIECE_AUTO_MIN
    while (size < PIECE_AUTO_MAX && totalBytes / size > 2048L) size *= 2
    return size
}

/** Nearest selectable piece size for an arbitrary byte count. */
private fun nearestPieceSize(bytes: Long): Long =
    PIECE_SIZES.minByOrNull { kotlin.math.abs(it - bytes) } ?: (4L * 1024 * 1024)

/** 制作种子（BEP-3 v1 .torrent）：文件/文件夹、分块、Tracker 分层、私有标记、进度与取消。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTorrentScreen(
    store: AppStore,
    onBack: () -> Unit,
) {
    var inputs by remember { mutableStateOf<List<CreateTorrentInput>>(emptyList()) }
    var pieceLength by remember { mutableStateOf(4L * 1024 * 1024) }
    var autoPiece by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var announce by remember { mutableStateOf("") }
    var extraTrackers by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var privateTorrent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(com.typebit.engine.MakeTorrentProgress()) }
    var doneBytes by remember { mutableStateOf<ByteArray?>(null) }
    var doneName by remember { mutableStateOf("") }
    var resultInfo by remember { mutableStateOf<com.typebit.engine.TorrentInfoDto?>(null) }
    val scope = rememberCoroutineScope()

    // Total payload size drives the auto piece size and the estimate row.
    val totalBytes = remember(inputs) { inputs.sumOf { FileIO.size(it.absPath).coerceAtLeast(0L) } }

    val picker = rememberCreateTorrentPicker { picked ->
        inputs = picked
        if (name.isBlank() && picked.isNotEmpty()) {
            // A folder pick yields `["<folder>", …]`; a file pick `["<name>.ext"]`.
            name = picked[0].relPath.first().substringBeforeLast('.')
        }
        if (autoPiece) pieceLength = nearestPieceSize(recommendPieceLength(totalBytes))
        doneBytes = null
        resultInfo = null
        status = null
    }

    // Staged copies (Android) are only needed while this screen lives.
    DisposableEffect(Unit) { onDispose { picker.cleanup() } }

    // Progress polling: the native maker publishes done/total atomically and
    // the UI samples it while a build is in flight.
    LaunchedEffect(busy) {
        if (busy) {
            while (true) {
                progress = store.makeTorrentProgress()
                if (!progress.running) break
                delay(150)
            }
        } else {
            progress = com.typebit.engine.MakeTorrentProgress()
        }
    }

    val savePicker = rememberSaveTorrentPicker(
        data = doneBytes ?: ByteArray(0),
        defaultName = doneName.ifBlank { "new.torrent" },
        onDone = { ok -> status = if (ok) "已保存 .torrent" else "保存失败或已取消" },
    )

    val create = create@{
        if (inputs.isEmpty()) {
            status = "请先选择要打包的文件或文件夹"
            return@create
        }
        val effectiveName = name.trim().ifBlank { "torrent" }
        val trackers =
            (listOf(announce) + extraTrackers.lines())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        busy = true
        status = null
        doneBytes = null
        resultInfo = null
        scope.launch {
            val options =
                com.typebit.engine.MakeTorrentOptions(
                    files = inputs.map { it.absPath to it.relPath },
                    pieceLength = pieceLength.toInt(),
                    name = effectiveName,
                    announce = trackers,
                    comment = comment.trim().ifBlank { null },
                    source = source.trim().ifBlank { null },
                    isPrivate = privateTorrent,
                )
            val bytes =
                withContext(Dispatchers.Default) {
                    runCatching { store.makeTorrent(options) }.getOrNull()
                }
            busy = false
            if (bytes == null || bytes.isEmpty()) {
                status =
                    if (progress.cancelled) "已取消制作"
                    else "制作失败：无法读取文件或参数无效（文件名含非法字符、文件被修改或被占用）"
            } else {
                doneBytes = bytes
                doneName = "$effectiveName.torrent"
                // Show the REAL identity of what was produced (infohash from
                // the engine's own parser, not a re-implementation).
                resultInfo =
                    withContext(Dispatchers.Default) {
                        runCatching { store.parseTorrentFile(bytes) }.getOrNull()
                    }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = picker.pickFiles, enabled = !busy) {
                            Icon(Icons.Default.Build, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("选择文件")
                        }
                        OutlinedButton(onClick = picker.pickFolder, enabled = !busy) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("选择文件夹")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // The list can be long for a folder pick: cap the rows
                    // rendered and keep the container non-scrolling (the page
                    // itself scrolls).
                    if (inputs.isNotEmpty()) {
                        Text(
                            "已选 ${inputs.size} 个文件 · ${Format.bytes(totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    inputs.take(60).forEach { input ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                input.relPath.joinToString("/"),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                Format.bytes(FileIO.size(input.absPath).coerceAtLeast(0L)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = { inputs = inputs - input }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "移除",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    if (inputs.size > 60) {
                        Text(
                            "…另有 ${inputs.size - 60} 个文件（已全部包含）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (inputs.isEmpty()) {
                        Text(
                            "尚未选择内容；选择文件夹会保留内部目录结构（Android 上会先复制到应用缓存）。",
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
                        Text("自动分块", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = autoPiece,
                            onCheckedChange = {
                                autoPiece = it
                                if (it) pieceLength = nearestPieceSize(recommendPieceLength(totalBytes))
                            },
                        )
                        Text(
                            if (autoPiece) {
                                "推荐 ${
                                    pieceLabel(pieceLength)
                                } · 约 ${if (pieceLength > 0) totalBytes / pieceLength + 1 else 0} 块"
                            } else {
                                "分块越大校验开销越低，但单块重试代价更高"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!autoPiece) {
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
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        placeholder = { Text("种子名（默认取自所选文件/文件夹）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = announce,
                        onValueChange = { announce = it },
                        label = { Text("主 Tracker（可选）") },
                        placeholder = { Text("http://…/announce 或 udp://…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = extraTrackers,
                        onValueChange = { extraTrackers = it },
                        label = { Text("附加 Tracker（每行一条，写入 announce-list）") },
                        placeholder = { Text("https://tracker.example/announce") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it },
                        label = { Text("Source 标记（可选，私有站点交叉做种用）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("私有种子 (BEP-27)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = privateTorrent, onCheckedChange = { privateTorrent = it })
                        Text(
                            "私有种子只向声明的 Tracker 汇报，禁用 DHT/PEX/LSD 传播",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
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

            if (busy) {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "正在校验分块 ${Format.bytes(progress.doneBytes)} / ${
                                Format.bytes(progress.totalBytes)
                            }",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { store.cancelMakeTorrent() }) { Text("取消") }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { create() },
                    enabled = !busy && inputs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (busy) "正在制作…" else "创建 .torrent")
                }
                if (doneBytes != null) {
                    OutlinedButton(onClick = {
                        val bytes = doneBytes
                        val fileName = doneName.ifBlank { "new.torrent" }
                        if (bytes != null) {
                            // Track what was just created right away — the
                            // usual next step after making a torrent is to
                            // seed it. The engine starts a session with an
                            // empty bitfield, so the files it was just built
                            // from are only visible to it after one
                            // verification pass — without it the client would
                            // re-download its own source data.
                            val hash = store.parseTorrentFile(bytes)?.hash
                            store.addTorrentFile(bytes, fileName)
                            if (hash != null) store.recheck(hash)
                            status = "已添加到下载列表（正在校验本地数据，完成后即可做种）"
                        }
                    }) {
                        Text("添加并做种")
                    }
                    Button(onClick = { savePicker() }) { Text("保存文件") }
                }
            }

            resultInfo?.let { info ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "创建结果",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        ResultRow("名称", info.name)
                        ResultRow("信息哈希", info.hash)
                        ResultRow("大小", Format.bytes(info.size))
                        ResultRow("分块", "${info.piece_count} × ${Format.bytes(info.piece_length)}")
                        ResultRow("文件数", info.files.size.toString())
                        ResultRow("Tracker", info.trackers.size.toString())
                        if (info.`private`) ResultRow("私有种子", "是")
                    }
                }
            }

            HorizontalDivider()
            Text(
                "生成的是 BEP-3 v1 种子：分块 16 KiB ~ 256 MiB，支持 announce-list 分层、private (BEP-27) 与 source 标记；" +
                    "同一份内容无论选择顺序如何都会得到相同的信息哈希。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
