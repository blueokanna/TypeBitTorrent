package com.typebit.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/**
 * Desktop picker: a multi-file selection builds a flat torrent, a folder
 * selection builds a multi-file torrent whose root is the folder itself
 * (qBittorrent behaviour). `JFileChooser` is used instead of `FileDialog`
 * because it selects directories reliably on Windows and Linux too.
 */
@Composable
actual fun rememberCreateTorrentPicker(
    onPicked: (List<CreateTorrentInput>) -> Unit,
): CreateTorrentPicker {
    return remember {
        val pickFiles = {
            val chooser =
                    JFileChooser().apply {
                        dialogTitle = "选择要打包的文件（可多选）"
                        isMultiSelectionEnabled = true
                        fileSelectionMode = JFileChooser.FILES_ONLY
                    }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val picked =
                        chooser.selectedFiles
                                .filter { it.isFile }
                                .map { CreateTorrentInput(it.absolutePath, listOf(it.name)) }
                if (picked.isNotEmpty()) onPicked(picked)
            }
        }
        val pickFolder = {
            val chooser =
                    JFileChooser().apply {
                        dialogTitle = "选择要打包的文件夹"
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val root = chooser.selectedFile
                if (root != null && root.isDirectory) {
                    // The folder name becomes the torrent root directory, so
                    // the rel paths start with it (mirrors how a folder
                    // torrent is laid out).
                    val picked =
                            walkDirectory(root).map { rel ->
                                CreateTorrentInput(
                                        File(root, rel.joinToString("/")).absolutePath,
                                        listOf(root.name) + rel,
                                )
                            }
                    if (picked.isNotEmpty()) onPicked(picked)
                }
            }
        }
        CreateTorrentPicker(pickFiles = pickFiles, pickFolder = pickFolder)
    }
}

/** Recursively lists a directory's files, each as path components relative to [root]. */
private fun walkDirectory(root: File): List<List<String>> {
    val out = ArrayList<List<String>>()
    fun walk(dir: File, prefix: List<String>) {
        val children = dir.listFiles() ?: return
        for (c in children.sortedBy { it.name }) {
            when {
                c.isDirectory -> walk(c, prefix + c.name)
                c.isFile -> out.add(prefix + c.name)
            }
        }
    }
    walk(root, emptyList())
    return out
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
