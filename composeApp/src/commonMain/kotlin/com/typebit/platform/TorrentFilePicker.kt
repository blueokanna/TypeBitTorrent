package com.typebit.platform

import androidx.compose.runtime.Composable

/**
 * A platform torrent-file picker. Returns a launcher lambda; when the user
 * picks a file, `onPicked(bytes, fileName)` fires with the raw `.torrent`
 * bytes. Desktop uses AWT's `FileDialog`; Android uses the Storage Access
 * Framework via Activity Result.
 */
@Composable
expect fun rememberTorrentFilePicker(onPicked: (ByteArray, String) -> Unit): () -> Unit
