package com.typebit.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.platform.Platform
import com.typebit.search.NyaaEngine
import com.typebit.search.PirateBayEngine
import com.typebit.search.SearchEngineProgress
import com.typebit.search.SearchHttpClient
import com.typebit.search.SearchPhase
import com.typebit.search.TorrentSearchClient
import com.typebit.search.TorrentSearchResult
import com.typebit.search.X1337xEngine
import com.typebit.store.AppState
import com.typebit.store.AppStore
import com.typebit.ui.components.EmptyState
import com.typebit.ui.components.StatusBadge
import com.typebit.ui.util.Format
import kotlinx.coroutines.launch

/**
 * Integrated search screen: type a query, tap 搜索 and the app crawls the
 * configured torrent sites (Nyaa / 1337x / The Pirate Bay) in the background
 * — no browser, no manual link-clicking. Online-video / cloud-drive results
 * are filtered out; every result carries a real magnet and can be added to
 * the download list in one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: AppState,
    store: AppStore,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<TorrentSearchResult>>(emptyList()) }
    val progress = remember { mutableStateMapOf<String, SearchEngineProgress>() }
    val addedMagnets = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()

    val searchClient = remember {
        TorrentSearchClient(
            listOf(
                NyaaEngine(SearchHttpClient()),
                X1337xEngine(SearchHttpClient()),
                PirateBayEngine(SearchHttpClient()),
            ),
        )
    }

    val localResults: List<Torrent> =
        if (query.isBlank()) emptyList()
        else state.torrents.filter { it.name.contains(query.trim(), ignoreCase = true) }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        results = emptyList()
        progress.clear()
        addedMagnets.clear()
        scope.launch {
            results =
                searchClient.search(q) { p ->
                    progress[p.name] = p
                }
            searching = false
        }
    }

    fun addResult(r: TorrentSearchResult) {
        val saveDir =
            state.settings.downloads.defaultSavePath.ifBlank { Platform.defaultDownloadDir() }
        store.addMagnetEx(r.magnet, saveDir, "", emptyList(), paused = false)
        addedMagnets[r.magnet] = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索种子（自动抓取 Nyaa / 1337x / TPB）…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { runSearch() },
                    enabled = query.isNotBlank() && !searching,
                ) {
                    if (searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (searching) "搜索中" else "搜索")
                }
            }

            // Live per-engine progress.
            if (progress.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    progress.values.forEach { p ->
                        EngineStatusRow(p)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            when {
                results.isNotEmpty() -> {
                    Text(
                        "搜索结果 ${results.size} 条（已过滤在线视频/网盘）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(results, key = { it.magnet }) { r ->
                            SearchResultCard(
                                result = r,
                                added = addedMagnets[r.magnet] == true,
                                onAdd = { addResult(r) },
                            )
                        }
                    }
                }
                !searching && progress.isNotEmpty() -> {
                    EmptyState(
                        title = "没有可用的磁力结果",
                        subtitle = "所有站点均被反爬/不可达，或没有匹配。可稍后重试或更换关键词。",
                    )
                }
                localResults.isNotEmpty() -> {
                    Text(
                        "本机匹配 ${localResults.size} 个种子",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(localResults, key = { it.hash }) { t ->
                            Card(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${Format.bytes(t.sizeBytes)} · ${Format.percent(t.progress)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    StatusBadge(t.status)
                                }
                            }
                        }
                    }
                }
                else -> {
                    EmptyState(
                        title = "输入关键词开始搜索",
                        subtitle = "自动抓取 Nyaa、1337x、The Pirate Bay，返回真实磁力链接",
                    )
                }
            }
        }
    }
}

/** One engine's live status during a search. */
@Composable
private fun EngineStatusRow(p: SearchEngineProgress) {
    val (label, color) =
        when (p.phase) {
            SearchPhase.RUNNING -> "搜索中…" to MaterialTheme.colorScheme.primary
            SearchPhase.DONE -> "完成 · ${p.count} 条" to Color(0xFF2E7D32)
            SearchPhase.BLOCKED -> "被反爬拦截" to MaterialTheme.colorScheme.error
            SearchPhase.FAILED -> "不可达" to MaterialTheme.colorScheme.error
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (p.phase == SearchPhase.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                p.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

/** One search result with a one-tap "add to downloads" action. */
@Composable
private fun SearchResultCard(
    result: TorrentSearchResult,
    added: Boolean,
    onAdd: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(result.source)
                        if (result.size.isNotBlank()) append(" · ${result.size}")
                        append(" · ${result.seeds} 种 / ${result.leeches} 下载者")
                        if (result.date.isNotBlank()) append(" · ${result.date}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (added) {
                OutlinedButton(onClick = {}, enabled = false) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("已添加")
                }
            } else {
                Button(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加")
                }
            }
        }
    }
}
