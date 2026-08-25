package com.typebit.ui.wallpaper

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.roundToInt

/**
 * Cross-platform Gaussian-approximating blur used by the wallpaper pipeline.
 *
 * The previous approach applied `Modifier.blur` to the full-screen wallpaper,
 * which re-runs an expensive GPU blur on EVERY animation frame (route
 * transitions, opening the settings screen, slider drags). That per-frame
 * cost is the source of the jank you feel when adjusting wallpaper settings.
 *
 * Instead the raw (already downscaled) bitmap is blurred ONCE off the UI
 * thread and the result is cached and reused. Three box-blur passes
 * approximate a Gaussian closely enough for a background while staying O(n)
 * and allocation-light.
 *
 * The blurred result is a plain [ImageBitmap] — [WallpaperLayer] just draws
 * it, so drawing it every frame is a cheap static blit.
 */
fun blurWallpaper(src: ImageBitmap, radiusPx: Float): ImageBitmap {
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0 || radiusPx <= 0.5f) return src

    // 3 box passes ≈ Gaussian; a box radius ≈ half the requested radius keeps
    // the visual weight close to the old `Modifier.blur` while staying cheap.
    val boxRadius = (radiusPx * 0.5f).roundToInt().coerceIn(1, 40)

    val pixels = IntArray(w * h)
    src.readPixels(pixels, 0, 0, w, h, 0, w)
    val tmp = IntArray(w * h)
    repeat(3) { boxBlur(pixels, tmp, w, h, boxRadius) }
    return createImageBitmapFromPixels(pixels, w, h)
}

/**
 * Working edge length for the background draw bitmap. Raised to 1440 so the
 * blurred, dimmed background stays crisp on modern screens (a 640px source
 * looked soft/pixelated on hi-DPI displays). 1440px ≈ 4.7 MiB, blurred once
 * off the UI thread and cached — drawing it each frame is still one static
 * GPU blit, so low-end Android devices stay smooth.
 */
private const val MAX_BG_EDGE = 1440

/**
 * Full pipeline for the app background: downscale to a cheap draw size, then
 * blur. Returns the source unchanged when no change is needed (radius ≈ 0
 * and the image already small).
 */
fun prepareWallpaper(src: ImageBitmap, radiusPx: Float): ImageBitmap {
    val small = downscaleImage(src, MAX_BG_EDGE)
    return blurWallpaper(small, radiusPx)
}

/** Nearest-neighbour downscale so the longest edge is at most [maxEdge]. */
fun downscaleImage(src: ImageBitmap, maxEdge: Int): ImageBitmap {
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0 || maxOf(w, h) <= maxEdge) return src
    val scale = maxEdge.toFloat() / maxOf(w, h)
    val tw = (w * scale).toInt().coerceAtLeast(1)
    val th = (h * scale).toInt().coerceAtLeast(1)
    val pixels = IntArray(w * h)
    src.readPixels(pixels, 0, 0, w, h, 0, w)
    val out = IntArray(tw * th)
    val sxStep = w.toFloat() / tw
    val syStep = h.toFloat() / th
    for (ty in 0 until th) {
        val sy = (ty * syStep).toInt()
        val srcRow = sy * w
        val dstRow = ty * tw
        for (tx in 0 until tw) {
            out[dstRow + tx] = pixels[srcRow + (tx * sxStep).toInt()]
        }
    }
    return createImageBitmapFromPixels(out, tw, th)
}

/** Creates a bitmap from an ARGB [pixels] buffer (same layout as readPixels). */
expect fun createImageBitmapFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap

/** One box-blur pass: horizontal sweep into [tmp], then vertical sweep back. */
private fun boxBlur(pixels: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
    boxBlurH(pixels, tmp, w, h, r)
    boxBlurV(tmp, pixels, w, h, r)
}

private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val div = (r shl 1) + 1
    var y = 0
    while (y < h) {
        val row = y * w
        var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
        for (i in -r..r) {
            val c = src[row + i.coerceIn(0, w - 1)]
            sumR += (c ushr 16) and 0xFF
            sumG += (c ushr 8) and 0xFF
            sumB += c and 0xFF
            sumA += (c ushr 24) and 0xFF
        }
        var x = 0
        while (x < w) {
            dst[row + x] =
                (((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div))
            val outX = (x - r).coerceIn(0, w - 1)
            val inX = (x + r + 1).coerceIn(0, w - 1)
            val o = src[row + outX]
            val i = src[row + inX]
            sumR += ((i ushr 16) and 0xFF) - ((o ushr 16) and 0xFF)
            sumG += ((i ushr 8) and 0xFF) - ((o ushr 8) and 0xFF)
            sumB += (i and 0xFF) - (o and 0xFF)
            sumA += ((i ushr 24) and 0xFF) - ((o ushr 24) and 0xFF)
            x++
        }
        y++
    }
}

private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val div = (r shl 1) + 1
    var x = 0
    while (x < w) {
        var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
        for (i in -r..r) {
            val c = src[(i.coerceIn(0, h - 1)) * w + x]
            sumR += (c ushr 16) and 0xFF
            sumG += (c ushr 8) and 0xFF
            sumB += c and 0xFF
            sumA += (c ushr 24) and 0xFF
        }
        var y = 0
        while (y < h) {
            dst[y * w + x] =
                (((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div))
            val outY = (y - r).coerceIn(0, h - 1)
            val inY = (y + r + 1).coerceIn(0, h - 1)
            val o = src[outY * w + x]
            val i = src[inY * w + x]
            sumR += ((i ushr 16) and 0xFF) - ((o ushr 16) and 0xFF)
            sumG += ((i ushr 8) and 0xFF) - ((o ushr 8) and 0xFF)
            sumB += (i and 0xFF) - (o and 0xFF)
            sumA += ((i ushr 24) and 0xFF) - ((o ushr 24) and 0xFF)
            y++
        }
        x++
    }
}
