@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package com.typebit.platform

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.typebit.AppContextHolder
import java.io.File

// FileProvider URI, because a plain `file://` throws on API 24+. The APPLICATION context means the
// intent needs `FLAG_ACTIVITY_NEW_TASK` (also on the chooser, which is what gets started), and the MIME
// type must come from the original name — a staged `.part` suffix would open the wrong picker.
actual fun playMediaFile(path: String): Boolean {
    val file = File(path)
    if (!file.isFile || file.length() == 0L) return false
    return try {
        val context = AppContextHolder.context
        val uri: Uri =
                FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                )
        val view =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, guessMime(mediaName(path)))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        context.startActivity(
                Intent.createChooser(view, "播放").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}

private fun mediaName(path: String): String =
        if (path.endsWith(".part", ignoreCase = true)) path.dropLast(5) else path

private fun guessMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts", "m2ts" -> "video/mp2t"
    "flv" -> "video/x-flv"
    "wmv" -> "video/x-ms-wmv"
    "mpg", "mpeg" -> "video/mpeg"
    "rmvb", "rm" -> "application/vnd.rn-realmedia"
    "3gp" -> "video/3gpp"
    "ogv" -> "video/ogg"
    // Audio containers: must not force a video-only resolver.
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "wav" -> "audio/wav"
    "ogg", "opus" -> "audio/ogg"
    else -> "*/*"
}
