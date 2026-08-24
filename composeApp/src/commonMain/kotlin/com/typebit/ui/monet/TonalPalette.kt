package com.typebit.ui.monet

import kotlin.math.roundToInt

/**
 * A tonal palette: 100 HCT colors of one hue/chroma across all tones.
 * Material You builds every surface/container color from these.
 */
class TonalPalette internal constructor(
    private val hue: Double,
    private val chroma: Double,
) {
    private val cache = HashMap<Int, Int>()

    /** ARGB at a given tone (0..100). Cached — palette access is hot. */
    fun tone(t: Double): Int {
        val key = t.roundToInt()
        return cache.getOrPut(key) { hctToArgb(hue, chroma, t) }
    }

    companion object {
        fun of(hue: Double, chroma: Double): TonalPalette = TonalPalette(hue, chroma)

        /** Palette for a seed color. */
        fun of(argb: Int): TonalPalette {
            val h = hctOf(argb)
            return TonalPalette(h.hue, h.chroma)
        }
    }
}
