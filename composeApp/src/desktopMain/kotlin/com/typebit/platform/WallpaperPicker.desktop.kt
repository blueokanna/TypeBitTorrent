package com.typebit.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

@Composable
actual fun rememberWallpaperPicker(onPicked: (String) -> Unit): () -> Unit {
    return remember {
        {
            val dialog = FileDialog(null as Frame?, "选择壁纸图片", FileDialog.LOAD)
            dialog.setFilenameFilter(
                FilenameFilter { _, name ->
                    val lower = name.lowercase()
                    lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                        lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".gif")
                },
            )
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir != null && file != null) {
                val f = File(dir, file)
                if (f.isFile) {
                    // Hand the result to the caller off the picker call stack.
                    onPicked(f.absolutePath)
                }
            }
        }
    }
}
