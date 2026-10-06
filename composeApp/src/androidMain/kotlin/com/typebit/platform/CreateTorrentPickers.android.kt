package com.typebit.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * Android picker for torrent creation.
 *
 * SAF returns `content://` URIs, which the Rust maker cannot open, so every
 * picked byte is staged into a private cache directory first and the torrent
 * is built from those staged paths (removed again when the screen finishes
 * with them).
 *
 * Two correctness details that the previous version got wrong:
 * * the display name comes from `OpenableColumns.DISPLAY_NAME` — deriving it
 *   from `lastPathSegment` yielded names like `primary` (the URI's document
 *   *authority* prefix), so multi-file torrents were built with garbage file
 *   names;
 * * folder picks walk the SAF tree (`DocumentsContract`) and keep each
 *   document's directory structure, so a folder torrent is a real folder
 *   torrent instead of a flat pile of files.
 */
@Composable
actual fun rememberCreateTorrentPicker(
    onPicked: (List<CreateTorrentInput>) -> Unit,
): CreateTorrentPicker {
    val context = LocalContext.current

    fun stage(): File {
        val dir = File(context.cacheDir, "create_torrent").apply { mkdirs() }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        return dir
    }

    /** Copies `uri` to `target`, creating parents; true on success. */
    fun copyTo(uri: Uri, target: File): Boolean =
        try {
            target.parentFile?.mkdirs()
            val ok = context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            ok
        } catch (_: Exception) {
            false
        }

    val filesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            val stageDir = stage()
            val picked = ArrayList<CreateTorrentInput>(uris.size)
            val used = HashSet<String>()
            for (uri in uris) {
                val name = displayName(context, uri) ?: "file"
                var target = File(stageDir, name)
                var i = 1
                while (!used.add(target.name)) {
                    val base = name.substringBeforeLast('.')
                    val ext = name.substringAfterLast('.', "")
                    target = File(stageDir, if (ext.isEmpty()) "${base}_$i" else "${base}_$i.$ext")
                    i++
                }
                if (copyTo(uri, target)) {
                    picked.add(CreateTorrentInput(target.absolutePath, listOf(target.name)))
                }
            }
            if (picked.isNotEmpty()) onPicked(picked)
        }

    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val rootName = displayName(context, uri) ?: "torrent"
            val stageDir = File(stage(), rootName).apply { mkdirs() }
            val entries = collectTree(context, uri)
            if (entries.isEmpty()) return@rememberLauncherForActivityResult
            val picked = ArrayList<CreateTorrentInput>(entries.size)
            for ((rel, child) in entries) {
                val target = File(stageDir, rel.joinToString("/"))
                if (copyTo(child, target)) {
                    picked.add(CreateTorrentInput(target.absolutePath, listOf(rootName) + rel))
                }
            }
            if (picked.isNotEmpty()) onPicked(picked)
        }

    return remember(context) {
        CreateTorrentPicker(
            pickFiles = { filesLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            pickFolder = { folderLauncher.launch(null) },
            // The staged copies exist only to give the native maker a real
            // path; they are dead weight once the build is done.
            cleanup = {
                runCatching { File(context.cacheDir, "create_torrent").deleteRecursively() }
                Unit
            },
        )
    }
}

/** Resolves the user-visible file name of a SAF document. */
private fun displayName(context: Context, uri: Uri): String? =
    try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
    } catch (_: Exception) {
        null
    }

/**
 * Depth-first walk of a SAF tree, returning `(relative path, document)` pairs
 * for every non-directory document.
 */
private fun collectTree(context: Context, treeUri: Uri): List<Pair<List<String>, Uri>> {
    val out = ArrayList<Pair<List<String>, Uri>>()
    val rootId =
        try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            null
        } ?: return out

    fun walk(documentId: String, prefix: List<String>) {
        val children =
            try {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            } catch (_: Exception) {
                return
            }
        try {
            context.contentResolver
                .query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )
                ?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        // Skip names that cannot be represented in a torrent.
                        if (name == "." || name == ".." || name.contains('/')) continue
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(id, prefix + name)
                        } else {
                            out.add(
                                (prefix + name) to
                                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            )
                        }
                    }
                }
        } catch (_: Exception) {
            // A single unreadable subdirectory must not abort the whole pick.
        }
    }
    walk(rootId, emptyList())
    return out
}

@Composable
actual fun rememberSaveTorrentPicker(
    data: ByteArray,
    defaultName: String,
    onDone: (Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-bittorrent"),
    ) { uri ->
        if (uri == null) {
            onDone(false)
            return@rememberLauncherForActivityResult
        }
        onDone(
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(data) } != null
            } catch (_: Exception) {
                false
            },
        )
    }
    return remember(data, defaultName) {
        {
            launcher.launch(defaultName)
        }
    }
}
