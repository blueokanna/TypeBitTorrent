package com.typebit.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
actual fun rememberCreateTorrentPicker(
    onPicked: (List<Pair<String, String>>) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // Stage picked files into the app cache so the native maker can read
        // real paths (SAF URIs are not addressable from Rust).
        val stageDir = File(context.cacheDir, "create_torrent").apply { mkdirs() }
        stageDir.listFiles()?.forEach { it.delete() }
        val picked = ArrayList<Pair<String, String>>(uris.size)
        val used = HashSet<String>()
        for (uri in uris) {
            val raw = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val name = raw.substringBefore(':').ifBlank { "file" }
            var staged = File(stageDir, name)
            var i = 1
            while (!used.add(staged.name)) {
                staged = File(stageDir, name.substringBeforeLast('.') + "_$i." + name.substringAfterLast('.', ""))
                i++
            }
            val ok = context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (ok) picked.add(staged.absolutePath to staged.name)
        }
        if (picked.isNotEmpty()) onPicked(picked)
    }
    return remember {
        {
            launcher.launch(arrayOf("application/octet-stream", "*/*"))
        }
    }
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
