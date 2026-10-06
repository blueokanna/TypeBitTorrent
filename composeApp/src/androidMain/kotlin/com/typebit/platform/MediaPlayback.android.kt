@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package com.typebit.platform

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.typebit.AppContextHolder
import java.io.File

/**
 * Android: hand the file to the system media player through a FileProvider
 * content URI (a plain `file://` throws `FileUriExposedException` on API 24+).
 * The URI grant is temporary and scoped to the receiving app.
 *
 * Two details here are load-bearing:
 *
 * 1. The context is the APPLICATION context, so the started intent MUST carry
 *    `FLAG_ACTIVITY_NEW_TASK` — without it `startActivity` throws
 *    `AndroidRuntimeException` and 边下边播 silently never opened. The flag
 *    goes on the chooser as well, because that is the intent actually started.
 * 2. While the torrent is downloading the staged file is `<name>.part`, so
 *    the MIME type is derived from the ORIGINAL name: a `.part` suffix would
 *    fall through to a generic type and open the wrong picker.
 */
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

/** The logical name of a staged file: `<name>.part` → `<name>`. */
private fun mediaName(path: String): String =
        if (path.endsWith(".part", ignoreCase = true)) path.dropLast(5) else path

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
    // Audio containers: the Files tab opens whatever the user taps, so the
    // MIME must not force a video-only resolver.
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "wav" -> "audio/wav"
    "ogg", "opus" -> "audio/ogg"
    else -> "*/*"
}
