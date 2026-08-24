package com.typebit.ui.wallpaper

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File

actual fun loadWallpaperBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return runCatching {
        val bytes = File(path).readBytes()
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}
