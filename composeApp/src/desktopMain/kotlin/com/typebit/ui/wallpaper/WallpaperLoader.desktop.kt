package com.typebit.ui.wallpaper

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.min

/** Working resolution cap — blur + redraw stay cheap on 4K wallpapers. */
private const val MAX_W = 1920
private const val MAX_H = 1080

actual fun loadWallpaperBitmap(path: String?): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    return runCatching {
        val src = ImageIO.read(File(path)) ?: return@runCatching null
        val w = src.width
        val h = src.height
        val scale = min(1f, min(MAX_W.toFloat() / w.toFloat(), MAX_H.toFloat() / h.toFloat()))
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB)
        val g: Graphics2D = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, tw, th, null)
        g.dispose()
        out.toComposeImageBitmap()
    }.getOrNull()
}
