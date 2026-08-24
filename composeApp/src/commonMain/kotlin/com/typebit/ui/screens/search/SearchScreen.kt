package com.typebit.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.platform.openInBrowser
import com.typebit.store.AppState
import com.typebit.store.AppStore
import com.typebit.ui.components.EmptyState
import com.typebit.ui.components.StatusBadge
import com.typebit.ui.util.Format

/** External torrent search engines (opened in the default browser). */
private data class SearchEngine(val name: String, val urlTemplate: String)

private val ENGINES = listOf(
    SearchEngine("Nyaa", "https://nyaa.si/?f=0&c=0_0&q=%s"),
    SearchEngine("1337x", "https://1337x.to/search/%s/1/"),
    SearchEngine("The Pirate Bay", "https://thepiratebay.org/search.php?q=%s"),
    SearchEngine("BTDigg", "https://btdig.com/search?q=%s"),
)

/**
 * Search screen: filters your local torrents and offers one-click external
 * search via the default browser. No scraping, no fake results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: AppState,
    store: AppStore,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val localResults: List<Torrent> =
        if (query.isBlank()) emptyList()
        else state.torrents.filter { it.name.contains(query.trim(), ignoreCase = true) }

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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索种子名称或哈希…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ENGINES.forEach { engine ->
                    OutlinedButton(
                        onClick = {
                            if (query.isNotBlank()) {
                                openInBrowser(engine.urlTemplate.replace("%s", com.typebit.ui.util.UrlCodec.encode(query.trim())))
                            }
                        },
                        enabled = query.isNotBlank(),
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(engine.name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (query.isBlank()) "输入关键词后，可在外站搜索或在本机种子里查找。"
                else "本机匹配 ${localResults.size} 个种子",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (localResults.isEmpty()) {
                EmptyState(
                    title = "无匹配结果",
                    subtitle = "尝试外站搜索，或更换关键词",
                )
            } else {
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
        }
    }
}
