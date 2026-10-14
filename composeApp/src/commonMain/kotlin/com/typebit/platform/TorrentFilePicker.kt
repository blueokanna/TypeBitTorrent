package com.typebit.platform

import androidx.compose.runtime.Composable

// Platform `.torrent` picker: `onPicked` receives the raw bytes (desktop AWT `FileDialog`; Android
// Storage Access Framework).
@Composable
expect fun rememberTorrentFilePicker(onPicked: (ByteArray, String) -> Unit): () -> Unit
