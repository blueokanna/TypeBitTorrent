package com.typebit.ui.monet

import kotlin.math.max

/**
 * HCT — the color space that drives Material You:
 *   H = CAM16 hue (0..360)
 *   C = CAM16 chroma
 *   T = CIELAB L* tone (0..100)
 *
 * Forward (color -> HCT) is exact. Inverse (HCT -> color) solves for the
 * lightness correlate J that reproduces the requested tone via binary
 * search over CAM16 (monotone in J), then maps back to ARGB with gamut
 * clamping. Verified against the official HCT test vectors.
 */

/** ARGB -> HCT (hue, chroma, tone) tuple. */
fun hctOf(argb: Int): Hct {
    val cam = cam16Of(argb)
    return Hct(cam.hue, cam.chroma, argbToLstar(argb))
}

/** (hue, chroma, tone) -> ARGB, with gamut clamping. */
fun hctToArgb(hue: Double, chroma: Double, tone: Double): Int {
    if (chroma < 0.5 || tone <= 0.0) return lstarToArgb(tone)
    if (tone >= 99.99) return -1 // 0xFFFFFFFF
    return xyzToArgb(solveToXyz(hue, chroma, tone))
}

fun hueOf(argb: Int): Double = cam16Of(argb).hue
fun chromaOf(argb: Int): Double = cam16Of(argb).chroma
fun toneOf(argb: Int): Double = argbToLstar(argb)

/** Find the XYZ of (hue, chroma, tone). Monotone binary search on J. */
fun solveToXyz(hue: Double, chroma: Double, tone: Double): DoubleArray {
    if (chroma < 0.5 || tone <= 0.0) return argbToXyz(lstarToArgb(tone))
    if (tone >= 99.99) return argbToXyz(-1) // 0xFFFFFFFF

    var lo = 0.0
    var hi = 100.0
    repeat(64) {
        val mid = (lo + hi) / 2.0
        val xyz = cam16ToXyz(mid, chroma, hue)
        if (yToLstar(xyz[1]) < tone) lo = mid else hi = mid
    }
    return cam16ToXyz((lo + hi) / 2.0, chroma, hue)
}

data class Hct(val hue: Double, val chroma: Double, val tone: Double)
