package com.typebit.platform

import androidx.compose.runtime.Composable

data class CreateTorrentInput(val absPath: String, val relPath: List<String>)

class CreateTorrentPicker(
    val pickFiles: () -> Unit,
    val pickFolder: () -> Unit,
    val cleanup: () -> Unit = {},
)

// Folder and multi-file picker for torrent creation; on Android the picked bytes are staged into the app
// cache first (a SAF `content://` URI is not addressable from Rust).
@Composable
expect fun rememberCreateTorrentPicker(
    onPicked: (List<CreateTorrentInput>) -> Unit,
): CreateTorrentPicker

@Composable
expect fun rememberSaveTorrentPicker(data: ByteArray, defaultName: String, onDone: (Boolean) -> Unit): () -> Unit
