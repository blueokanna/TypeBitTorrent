package com.typebit.platform

import androidx.compose.runtime.Composable

/**
 * One input file for torrent creation: where it lives on disk and where it
 * belongs inside the torrent (the relative path components stored in the
 * `info` dict, e.g. `["MyFolder", "sub", "a.bin"]`).
 */
data class CreateTorrentInput(val absPath: String, val relPath: List<String>)

/**
 * Two entry points for picking creation inputs — a multi-file selection and a
 * recursive folder selection — plus [cleanup], which releases any staging copy
 * the platform had to make (Android SAF). Holding all three keeps the UI
 * identical on every platform (desktop dialogs, Android SAF) without leaking
 * platform types.
 */
class CreateTorrentPicker(
    val pickFiles: () -> Unit,
    val pickFolder: () -> Unit,
    /** Removes staged copies of the picked files; a no-op where not needed. */
    val cleanup: () -> Unit = {},
)

/**
 * A multi-file / folder picker for torrent creation.
 *
 * `onPicked` receives absolute paths the native maker can open together with
 * the relative path each file must have in the torrent; on Android the bytes
 * are staged into the app cache first (a SAF `content://` URI is not
 * addressable from Rust).
 */
@Composable
expect fun rememberCreateTorrentPicker(
    onPicked: (List<CreateTorrentInput>) -> Unit,
): CreateTorrentPicker

/**
 * A save picker that writes the finished `.torrent` bytes to the user's
 * chosen location. `onDone(true)` fires after a successful write.
 */
@Composable
expect fun rememberSaveTorrentPicker(data: ByteArray, defaultName: String, onDone: (Boolean) -> Unit): () -> Unit
