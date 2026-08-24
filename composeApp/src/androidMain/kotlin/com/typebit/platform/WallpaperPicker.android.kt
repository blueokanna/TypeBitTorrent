package com.typebit.platform

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
actual fun rememberWallpaperPicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val saved = copyToAppData(context, uri)
            if (saved != null) onPicked(saved)
        }
    }
    return remember {
        { launcher.launch("image/*") }
    }
}

/**
 * Copies the picked content into app-private storage so the wallpaper path
 * stays valid across launches and across SAF URI expiry. Returns the new
 * absolute path, or null when the source can't be read.
 */
private fun copyToAppData(context: Context, uri: Uri): String? {
    return runCatching {
        val dir = File(Platform.appDataDir(), "wallpaper")
        if (!dir.exists()) dir.mkdirs()
        val ext = guessExtension(context, uri)
        val target = File(dir, "wallpaper.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    }.getOrNull()
}

private fun guessExtension(context: Context, uri: Uri): String {
    val mime = context.contentResolver.getType(uri)
    return when {
        mime == null -> "jpg"
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        mime.contains("bmp") -> "bmp"
        else -> "jpg"
    }
}
