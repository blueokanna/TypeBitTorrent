package com.typebit.ui.wallpaper

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

actual fun loadWallpaperBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return runCatching {
        val bytes = File(path).readBytes()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
