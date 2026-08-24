package com.typebit.ui.monet

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the HCT engine against the official Material You test vectors
 * (from material-color-utilities Cam16/Hct tests) plus exact round-trips.
 * If these fail, the dynamic-color pipeline is wrong.
 */
class HctTest {

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, what: String) {
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, got $actual")
    }

    private fun assertArgb(expected: Int, actual: Int, what: String) {
        assertTrue(
            abs(((expected shr 16) and 0xFF) - ((actual shr 16) and 0xFF)) <= 1 &&
                abs(((expected shr 8) and 0xFF) - ((actual shr 8) and 0xFF)) <= 1 &&
                abs((expected and 0xFF) - (actual and 0xFF)) <= 1,
            "$what: expected #${expected.toUInt().toString(16)}, got #${actual.toUInt().toString(16)}",
        )
    }

    @Test
    fun redOfficialVectors() {
        val hct = hctOf(0xFFFF0000.toInt())
        assertClose(27.408, hct.hue, 0.01, "red hue")
        assertClose(113.358, hct.chroma, 0.01, "red chroma")
        assertClose(53.237, hct.tone, 0.01, "red tone")
        assertArgb(0xFFFF0000.toInt(), hctToArgb(27.408, 113.358, 53.237), "red roundtrip")
    }

    @Test
    fun greenOfficialVectors() {
        val hct = hctOf(0xFF00FF00.toInt())
        assertClose(142.139, hct.hue, 0.01, "green hue")
        assertClose(108.410, hct.chroma, 0.01, "green chroma")
        assertClose(87.737, hct.tone, 0.01, "green tone")
        assertArgb(0xFF00FF00.toInt(), hctToArgb(142.139, 108.410, 87.737), "green roundtrip")
    }

    @Test
    fun blueOfficialVectors() {
        val hct = hctOf(0xFF0000FF.toInt())
        assertClose(282.788, hct.hue, 0.01, "blue hue")
        assertClose(87.230, hct.chroma, 0.01, "blue chroma")
        assertClose(32.302, hct.tone, 0.01, "blue tone")
        assertArgb(0xFF0000FF.toInt(), hctToArgb(282.788, 87.230, 32.302), "blue roundtrip")
    }

    @Test
    fun orangeOfficialVectors() {
        val hct = hctOf(0xFFFFA500.toInt())
        assertClose(71.264, hct.hue, 0.02, "orange hue")
        assertClose(60.526, hct.chroma, 0.02, "orange chroma")
        assertClose(74.932, hct.tone, 0.02, "orange tone")
    }

    @Test
    fun roundTripPreservesColor() {
        val colors = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFF00FF7F.toInt(), 0xFFFFA500.toInt(), 0xFF800080.toInt(),
            0xFF008080.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(),
            0xFF123456.toInt(), 0xFFB38867.toInt(), 0xFFEBEBEB.toInt(),
        )
        for (argb in colors) {
            val h = hctOf(argb)
            assertArgb(argb, hctToArgb(h.hue, h.chroma, h.tone), "roundtrip #${argb.toUInt().toString(16)}")
        }
    }

    @Test
    fun graysAreAchromatic() {
        assertEquals(0.0, chromaOf(0xFF000000.toInt()), 0.001, "black chroma")
        assertEquals(100.0, toneOf(0xFFFFFFFF.toInt()), 0.001, "white tone")
        assertEquals(0.0, toneOf(0xFF000000.toInt()), 0.001, "black tone")
    }
}
