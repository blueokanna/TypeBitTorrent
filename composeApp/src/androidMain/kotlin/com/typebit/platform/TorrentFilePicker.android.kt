package com.typebit.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberTorrentFilePicker(onPicked: (ByteArray, String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "torrent.torrent"
                onPicked(bytes, name)
            }
        }
    }
    return remember {
        {
            launcher.launch(
                arrayOf(
                    "application/x-bittorrent",
                    "application/octet-stream",
                    "*/*",
                ),
            )
        }
    }
}
