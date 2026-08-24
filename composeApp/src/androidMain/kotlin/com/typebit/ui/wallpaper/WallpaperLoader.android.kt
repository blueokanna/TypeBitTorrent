package com.typebit.ui.wallpaper

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/** Working resolution cap — blur + redraw stay cheap on 4K wallpapers. */
private const val MAX_W = 1920
private const val MAX_H = 1080

actual fun loadWallpaperBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return runCatching {
        val bytes = File(path).readBytes()
        // Decode bounds first, then sample down to the working resolution.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val full = BitmapFactory.Options().apply { inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, full)?.asImageBitmap()
    }.getOrNull()
}

private fun computeSampleSize(w: Int, h: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var s = 1
    while (w / (s * 2) >= MAX_W || h / (s * 2) >= MAX_H) s *= 2
    return s.coerceAtLeast(1)
}
