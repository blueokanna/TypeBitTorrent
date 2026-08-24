package com.typebit.ui.monet

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CAM16 — the color appearance model behind HCT (Material You).
 *
 * Forward: ARGB -> (hue, chroma, J). Inverse: (J, C, H) -> XYZ.
 * Faithfully reproduces the reference implementation's pipeline:
 *   CAT02 adaptation (with the D discounting factor `rgbD`), 0.42-power
 *   nonlinear compression through `fl`, opponent hue equations, and the
 *   standard J/C correlates. The default viewing conditions are the exact
 *   constants the reference library caches.
 */
class Cam16 internal constructor(
    val hue: Double,
    val chroma: Double,
    val j: Double,
)

/**
 * Default viewing conditions (D65 white, 200 lux, average surround) — the
 * exact cached constants from the reference implementation.
 */
private object Vc {
    const val N = 0.184186503
    const val AW = 29.981000900
    const val NBB = 1.016919255
    const val NCB = 1.016919255
    const val C = 0.689999998
    const val NC = 1.0
    const val FL = 0.388481468
    const val FL_ROOT = 0.789482653
    const val Z = 1.909169555
    val RGB_D = doubleArrayOf(1.021177769, 0.986307740, 0.933960497)
}

/** XYZ -> adapted cone responses (CAT02). */
private val XYZ_TO_CAM16RGB = arrayOf(
    doubleArrayOf(0.401288, 0.650173, -0.051461),
    doubleArrayOf(-0.250268, 1.204414, 0.045854),
    doubleArrayOf(-0.002079, 0.048952, 0.953127),
)

/** Adapted cone responses -> XYZ (CAT02 inverse). */
private val CAM16RGB_TO_XYZ = arrayOf(
    doubleArrayOf(1.86206786, -1.01125463, 0.14918677),
    doubleArrayOf(0.38752654, 0.62144744, -0.00897398),
    doubleArrayOf(-0.01584150, -0.03412294, 1.04996444),
)

private fun matMul(m: Array<DoubleArray>, v: DoubleArray): DoubleArray = doubleArrayOf(
    m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
    m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
    m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
)

private fun sanitizeDegrees(d: Double): Double {
    val x = d % 360.0
    return if (x < 0) x + 360.0 else x
}

/** Forward CAM16 from ARGB. */
fun cam16Of(argb: Int): Cam16 {
    val cone = matMul(XYZ_TO_CAM16RGB, argbToXyz(argb))
    val rD = Vc.RGB_D[0] * cone[0]
    val gD = Vc.RGB_D[1] * cone[1]
    val bD = Vc.RGB_D[2] * cone[2]

    // 0.42-power nonlinear compression through the luminance adaptation fl.
    val rAF = (Vc.FL * abs(rD) / 100.0).pow(0.42)
    val gAF = (Vc.FL * abs(gD) / 100.0).pow(0.42)
    val bAF = (Vc.FL * abs(bD) / 100.0).pow(0.42)
    val rA = sign(rD) * 400.0 * rAF / (rAF + 27.13)
    val gA = sign(gD) * 400.0 * gAF / (gAF + 27.13)
    val bA = sign(bD) * 400.0 * bAF / (bAF + 27.13)

    // Opponent color axes.
    val aOpp = (11.0 * rA - 12.0 * gA + bA) / 11.0
    val bOpp = (rA + gA - 2.0 * bA) / 9.0
    val u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0
    val p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0

    val hue = sanitizeDegrees(Math.toDegrees(atan2(bOpp, aOpp)))
    val ac = p2 * Vc.NBB
    val j = 100.0 * (ac / Vc.AW).pow(Vc.C * Vc.Z)

    val huePrime = if (hue < 20.14) hue + 360.0 else hue
    val eHue = 0.25 * (cos(Math.toRadians(huePrime) + 2.0) + 3.8)
    val p1 = 50000.0 / 13.0 * eHue * Vc.NC * Vc.NCB
    val t = p1 * hypot(aOpp, bOpp) / (u + 0.305)
    val alpha = (1.64 - 0.29.pow(Vc.N)).pow(0.73) * t.pow(0.9)
    val chroma = alpha * sqrt(j / 100.0)

    return Cam16(hue, chroma, j)
}

/** Inverse CAM16: (J, C, H) -> CIE XYZ. */
fun cam16ToXyz(j: Double, c: Double, h: Double): DoubleArray {
    val alpha = if (c == 0.0 || j == 0.0) 0.0 else c / sqrt(j / 100.0)
    val t = (alpha / (1.64 - 0.29.pow(Vc.N)).pow(0.73)).pow(1.0 / 0.9)
    val hRad = Math.toRadians(h)
    val eHue = 0.25 * (cos(hRad + 2.0) + 3.8)
    val ac = Vc.AW * (j / 100.0).pow(1.0 / Vc.C / Vc.Z)
    val p1 = 50000.0 / 13.0 * eHue * Vc.NC * Vc.NCB
    val p2 = ac / Vc.NBB
    val hSin = sin(hRad)
    val hCos = cos(hRad)
    val gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hCos + 108.0 * t * hSin)
    val a = gamma * hCos
    val b = gamma * hSin
    val rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0
    val gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0
    val bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0

    // Inverse compression (sign-preserving, 1/0.42 power).
    val rCBase = maxOf(0.0, 27.13 * abs(rA) / (400.0 - abs(rA)))
    val rC = sign(rA) * (100.0 / Vc.FL) * rCBase.pow(1.0 / 0.42)
    val gCBase = maxOf(0.0, 27.13 * abs(gA) / (400.0 - abs(gA)))
    val gC = sign(gA) * (100.0 / Vc.FL) * gCBase.pow(1.0 / 0.42)
    val bCBase = maxOf(0.0, 27.13 * abs(bA) / (400.0 - abs(bA)))
    val bC = sign(bA) * (100.0 / Vc.FL) * bCBase.pow(1.0 / 0.42)

    val rF = rC / Vc.RGB_D[0]
    val gF = gC / Vc.RGB_D[1]
    val bF = bC / Vc.RGB_D[2]

    return matMul(CAM16RGB_TO_XYZ, doubleArrayOf(rF, gF, bF))
}

private fun sign(v: Double): Double = if (v < 0) -1.0 else 1.0
