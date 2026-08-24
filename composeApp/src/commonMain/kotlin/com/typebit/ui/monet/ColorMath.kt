package com.typebit.ui.monet

import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Color math for the HCT/Monet engine: sRGB <-> XYZ (D65) <-> CIELAB L*.
 *
 * Pure Kotlin, no dependencies — the same pipeline the Material You tonal
 * palette is built on, implemented from the CAM16 / CIE colorimetry
 * definitions so it runs identically on desktop and Android.
 */
internal const val PI = 3.141592653589793

/** sRGB 8-bit channel -> linear light (0..1). */
internal fun srgbToLinear(c: Int): Double {
    val v = c / 255.0
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/** Linear light (0..1) -> sRGB 8-bit channel. */
internal fun linearToSrgb(c: Double): Int {
    val v = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
    return (v * 255.0 + 0.5).toInt().coerceIn(0, 255)
}

/** ARGB -> CIE XYZ (Y scaled to 0..100, D65). */
internal fun argbToXyz(argb: Int): DoubleArray {
    val r = srgbToLinear((argb shr 16) and 0xFF)
    val g = srgbToLinear((argb shr 8) and 0xFF)
    val b = srgbToLinear(argb and 0xFF)
    return doubleArrayOf(
        (0.41233895 * r + 0.35762064 * g + 0.18051042 * b) * 100.0,
        (0.2126 * r + 0.7152 * g + 0.0722 * b) * 100.0,
        (0.01932141 * r + 0.11916382 * g + 0.95034478 * b) * 100.0,
    )
}

/** CIE XYZ (Y in 0..100, D65) -> ARGB (clamped). */
internal fun xyzToArgb(xyz: DoubleArray): Int {
    val x = xyz[0] / 100.0
    val y = xyz[1] / 100.0
    val z = xyz[2] / 100.0
    val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
    val g = -0.969266 * x + 1.8760108 * y + 0.041556 * z
    val b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
    val r8 = linearToSrgb(r)
    val g8 = linearToSrgb(g)
    val b8 = linearToSrgb(b)
    return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
}

/** CIELAB L* (0..100) -> Y (0..100). */
internal fun lstarToY(lstar: Double): Double =
    if (lstar > 8.0) ((lstar + 16.0) / 116.0).pow(3.0) * 100.0
    else lstar / 903.2962962 * 100.0

/** Y (0..100) -> CIELAB L* (0..100). */
internal fun yToLstar(y: Double): Double {
    val e = y / 100.0
    return if (e > 0.0088564516) 116.0 * cbrt(e) - 16.0 else 903.2962962 * e
}

/** Gray ARGB from a plain L* (no chroma). */
internal fun lstarToArgb(lstar: Double): Int {
    val y = lstarToY(lstar) / 100.0
    val c = linearToSrgb(y)
    return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
}

/** ARGB -> L* (achromatic lightness of the color). */
internal fun argbToLstar(argb: Int): Double = yToLstar(argbToXyz(argb)[1])
