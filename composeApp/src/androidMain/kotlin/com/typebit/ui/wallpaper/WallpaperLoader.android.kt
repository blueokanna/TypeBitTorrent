package com.typebit.ui.wallpaper

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/** Decoded-memory cap of ~25 MiB (2500×2500×4 B): larger sources are sample-reduced to fit. */
private const val MAX_EDGE = 2500

actual fun loadWallpaperBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return runCatching {
        val bytes = File(path).readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val full =
            BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight)
            }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, full)?.asImageBitmap()
    }.getOrNull()
}

private fun computeSampleSize(w: Int, h: Int): Int {
    if (w <= 0 || h <= 0) return 1
    var s = 1
    while (maxOf(w, h) / (s * 2) >= MAX_EDGE) s *= 2
    return s.coerceAtLeast(1)
}
