package com.typebit.ui.wallpaper

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * HD loading cap: keep the wallpaper sharp (no quality loss on 4K source)
 * while staying inside a ~25 MiB decoded-memory budget (2500×2500×4 B).
 * Larger sources are scaled down with bilinear filtering; anything ≤ the
 * budget is returned at full resolution.
 */
private const val MAX_EDGE = 2500

actual fun loadWallpaperBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return runCatching {
        val src = ImageIO.read(File(path)) ?: return@runCatching null
        val w = src.width
        val h = src.height
        if (maxOf(w, h) <= MAX_EDGE) {
            // Full quality: no resampling at all.
            return@runCatching src.toComposeImageBitmap()
        }
        val scale = MAX_EDGE.toFloat() / maxOf(w, h)
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, tw, th, null)
        g.dispose()
        out.toComposeImageBitmap()
    }.getOrNull()
}
