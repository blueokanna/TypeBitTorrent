package com.typebit.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberCreateTorrentPicker(
    onPicked: (List<Pair<String, String>>) -> Unit,
): () -> Unit {
    return remember {
        {
            val dialog = FileDialog(null as Frame?, "选择要打包的文件（可多选）", FileDialog.LOAD)
            dialog.isMultipleMode = true
            dialog.isVisible = true
            val files = dialog.files
            if (files != null && files.isNotEmpty()) {
                val picked = files
                    .filter { it.isFile }
                    .map { it.absolutePath to it.name }
                if (picked.isNotEmpty()) onPicked(picked)
            }
        }
    }
}

@Composable
actual fun rememberSaveTorrentPicker(
    data: ByteArray,
    defaultName: String,
    onDone: (Boolean) -> Unit,
): () -> Unit {
    return remember(data, defaultName) {
        {
            val dialog = FileDialog(null as Frame?, "保存 .torrent", FileDialog.SAVE)
            dialog.file = defaultName
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) {
                val target = File(dir, file)
                onDone(
                    try {
                        if (!target.name.lowercase().endsWith(".torrent")) {
                            File(target.absolutePath + ".torrent").writeBytes(data)
                        } else {
                            target.writeBytes(data)
                        }
                        true
                    } catch (_: Exception) {
                        false
                    },
                )
            } else {
                onDone(false)
            }
        }
    }
}
