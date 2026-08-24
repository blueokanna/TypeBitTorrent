package com.typebit.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberTorrentFilePicker(onPicked: (ByteArray, String) -> Unit): () -> Unit {
    return remember {
        {
            val dialog = FileDialog(null as Frame?, "选择 .torrent 文件", FileDialog.LOAD)
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) {
                val f = File(dir, file)
                if (f.isFile) {
                    val bytes = f.readBytes()
                    val name = f.name
                    // Hand the result to the caller off the picker call stack.
                    onPicked(bytes, name)
                }
            }
        }
    }
}
