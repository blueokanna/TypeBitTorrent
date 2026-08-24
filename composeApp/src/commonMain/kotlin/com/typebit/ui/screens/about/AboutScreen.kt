package com.typebit.ui.screens.about

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typebit.platform.Platform
import com.typebit.platform.openInBrowser
import com.typebit.ui.components.LabelValueRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("TypeBitTorrent", style = MaterialTheme.typography.titleLarge)
            Text(
                "跨平台 BitTorrent 客户端 · Compose Multiplatform + Rust 引擎",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp)) {
                    LabelValueRow("版本", "0.1.0")
                    LabelValueRow("平台", Platform.name)
                    LabelValueRow("引擎核心", "typebit 0.1.0 (Rust, no_std)")
                    LabelValueRow("UI 框架", "Jetpack Compose Multiplatform (Material 3)")
                    LabelValueRow("协议", "BitTorrent v1/v2 · DHT (BEP-5) · PEX (BEP-11) · WebSeed")
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { openInBrowser("https://github.com/blueokanna/TypeBit") }) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("TypeBit 引擎")
                }
                OutlinedButton(onClick = { openInBrowser("https://docs.rs/typebit/0.1.0/typebit/") }) {
                    Icon(Icons.Default.Code, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("引擎文档")
                }
            }
        }
    }
}
