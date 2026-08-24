package com.typebit.ui.wallpaper

import androidx.compose.ui.graphics.ImageBitmap
import com.typebit.ui.monet.hctOf
import com.typebit.ui.monet.hctToArgb
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Extracts a Material-You style seed color from a wallpaper bitmap.
 *
 * Strategy: read a bounded central region (<=1024x1024, ARGB Ints) via
 * [ImageBitmap.readPixels], down-sample it to a coarse grid, convert every
 * sample to HCT, then take the circular mean hue (weighted by chroma so
 * vivid regions dominate) and the mean chroma; the seed is that hue/chroma
 * at tone 40. Deterministic and cheap, it feeds the exact same
 * [com.typebit.ui.monet.DynamicScheme] pipeline Android uses for
 * wallpaper-based dynamic color.
 *
 * Note: `PixelMap.get` changed to a packed ULong color in Compose 1.8, so we
 * read the classic ARGB Int buffer directly instead — stable across
 * desktop/Android.
 */
fun extractSeedColor(bitmap: ImageBitmap): Int {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return 0xFF005AC1.toInt()

    // Bound memory: sample a central region no larger than 1024x1024.
    val sampleW = minOf(w, 1024)
    val sampleH = minOf(h, 1024)
    val startX = (w - sampleW) / 2
    val startY = (h - sampleH) / 2
    val buffer = IntArray(sampleW * sampleH)
    bitmap.readPixels(buffer, startX, startY, sampleW, sampleH, 0, sampleW)

    // Sample a coarse grid (~32x32) to keep extraction fast even for huge images.
    val stepX = (sampleW / 32).coerceAtLeast(1)
    val stepY = (sampleH / 32).coerceAtLeast(1)

    var hueSin = 0.0
    var hueCos = 0.0
    var chromaSum = 0.0
    var chromaWeight = 0.0
    var count = 0

    var y = 0
    while (y < sampleH) {
        var x = 0
        while (x < sampleW) {
            val argb = buffer[y * sampleW + x]
            val alpha = (argb ushr 24) and 0xFF
            if (alpha > 128) {
                val hct = hctOf(argb)
                // Vivid pixels dominate the hue; near-gray pixels barely count.
                val wgt = hct.chroma.coerceAtLeast(0.0)
                val hRad = Math.toRadians(hct.hue)
                hueSin += sin(hRad) * wgt
                hueCos += cos(hRad) * wgt
                chromaSum += hct.chroma
                chromaWeight += 1.0
                count++
            }
            x += stepX
        }
        y += stepY
    }
    if (count == 0) return 0xFF005AC1.toInt()

    val hue = Math.toDegrees(atan2(hueSin, hueCos)).let { if (it < 0) it + 360.0 else it }
    val chroma = (chromaSum / chromaWeight).coerceIn(24.0, 160.0)
    return hctToArgb(hue, chroma, 40.0)
}
