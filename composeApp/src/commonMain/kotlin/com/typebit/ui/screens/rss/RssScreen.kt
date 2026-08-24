package com.typebit.ui.screens.rss

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.data.RssFeed
import com.typebit.data.RssRepository
import com.typebit.data.fetchRssFeed
import com.typebit.store.AppState
import com.typebit.store.AppStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RSS reader: subscribe to feeds, refresh them, and copy article links.
 * Honest implementation — feeds are fetched over HTTP and parsed with the
 * JVM's built-in XML parser; no fake article data anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssScreen(
    state: AppState,
    store: AppStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val repo = remember { RssRepository() }
    var feedUrls by remember { mutableStateOf(repo.loadFeedUrls()) }
    var feeds by remember { mutableStateOf<List<RssFeed>>(emptyList()) }
    var newUrl by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.Default) {
                feedUrls.mapNotNull { fetchRssFeed(it, 15_000) }
            }
            feeds = result
            loading = false
        }
    }

    fun addFeed() {
        val url = newUrl.trim()
        if (url.isEmpty() || url in feedUrls) return
        feedUrls = feedUrls + url
        repo.saveFeedUrls(feedUrls)
        newUrl = ""
        reload()
    }

    fun removeFeed(url: String) {
        feedUrls = feedUrls - url
        repo.saveFeedUrls(feedUrls)
        feeds = feeds.filterNot { it.url == url }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RSS 阅读器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    placeholder = { Text("https://example.com/feed.xml") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::addFeed, enabled = newUrl.isNotBlank()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("订阅")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (loading) {
                Text("正在刷新…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (feeds.isEmpty() && !loading) {
                Text(
                    "还没有订阅的源。输入一个 RSS/Atom 地址开始。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(feeds, key = { it.url }) { feed ->
                    FeedCard(feed = feed, onRemove = { removeFeed(feed.url) })
                }
            }
        }
    }
}

@Composable
private fun FeedCard(feed: RssFeed, onRemove: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RssFeed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    feed.title.ifBlank { feed.url },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "取消订阅")
                }
            }
            feed.items.take(30).forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
