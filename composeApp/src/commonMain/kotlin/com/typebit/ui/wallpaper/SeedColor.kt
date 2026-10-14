package com.typebit.ui.wallpaper

import androidx.compose.ui.graphics.ImageBitmap
import com.typebit.ui.monet.hctOf
import com.typebit.ui.monet.hctToArgb
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Material-You seed: chroma-weighted circular mean hue/chroma of a down-sampled central
 * region, taken at tone 40. The raw ARGB buffer is read because PixelMap became a
 * packed ULong in Compose 1.8.
 */
fun extractSeedColor(bitmap: ImageBitmap): Int {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return 0xFF005AC1.toInt()

    val sampleW = minOf(w, 1024)
    val sampleH = minOf(h, 1024)
    val startX = (w - sampleW) / 2
    val startY = (h - sampleH) / 2
    val buffer = IntArray(sampleW * sampleH)
    bitmap.readPixels(buffer, startX, startY, sampleW, sampleH, 0, sampleW)

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

/** Average WCAG relative luminance (0 = black, 1 = white); the theme boosts the DIM scrim
 * with it until text keeps 4.5:1 contrast. */
fun averageBrightness(bitmap: ImageBitmap): Float {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return 0.5f

    val sampleW = minOf(w, 256)
    val sampleH = minOf(h, 256)
    val startX = (w - sampleW) / 2
    val startY = (h - sampleH) / 2
    val buffer = IntArray(sampleW * sampleH)
    bitmap.readPixels(buffer, startX, startY, sampleW, sampleH, 0, sampleW)

    val step = 4
    var sum = 0.0
    var n = 0
    var y = 0
    while (y < sampleH) {
        var x = 0
        while (x < sampleW) {
            val argb = buffer[y * sampleW + x]
            if (((argb ushr 24) and 0xFF) > 128) {
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                fun lin(c: Int): Double {
                    val v = c / 255.0
                    return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
                }
                sum += 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
                n++
            }
            x += step
        }
        y += step
    }
    return if (n == 0) 0.5f else (sum / n).toFloat().coerceIn(0f, 1f)
}
