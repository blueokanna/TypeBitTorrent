@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package com.typebit.platform

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.typebit.AppContextHolder
import java.io.File

/**
 * Android: share the file with the system media player through a FileProvider
 * content URI (a plain `file://` throws FileUriExposedException on API 24+).
 * The URI grant is temporary and scoped to the receiving app.
 */
actual fun playMediaFile(path: String): Boolean {
    val file = File(path)
    if (!file.exists() || file.length() == 0L) return false
    return try {
        val context = AppContextHolder.context
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, guessMime(path))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "播放"))
        true
    } catch (_: Exception) {
        false
    }
}

/** Coarse MIME guess for the media player intent. */
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
    else -> "video/*"
}
