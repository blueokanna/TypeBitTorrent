package com.typebit.platform

import androidx.compose.runtime.Composable

/**
 * A multi-file picker for torrent creation. `onPicked` receives
 * `(absolutePath, fileName)` pairs; on Android the bytes are first staged
 * into the app cache so the native maker can read real file paths.
 */
@Composable
expect fun rememberCreateTorrentPicker(onPicked: (List<Pair<String, String>>) -> Unit): () -> Unit

/**
 * A save picker that writes the finished `.torrent` bytes to the user's
 * chosen location. `onDone(true)` fires after a successful write.
 */
@Composable
expect fun rememberSaveTorrentPicker(data: ByteArray, defaultName: String, onDone: (Boolean) -> Unit): () -> Unit
