package com.typebit.ui.monet

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Material You dynamic scheme built from a seed color using HCT.
 *
 * Mirrors the tonal-role tones of the reference dynamic scheme:
 *   primary/secondary/tertiary at tone 40 (light) / 80 (dark),
 *   containers at 90 / 30, the neutral surface ramp at 98→6, and the
 *   neutral-variant outline ramp. Secondary/tertiary chroma are scaled
 *   down; tertiary hue is rotated 60°. Error keeps the standard hue/chroma.
 *
 * Produces a full M3 [ColorScheme] so the rest of the app is plain Material 3.
 */
object DynamicScheme {

    private const val ERROR_HUE = 25.0
    private const val ERROR_CHROMA = 84.0

    /** Primary chroma stays at the seed's; others are scaled. */
    private class Roles(seed: Hct) {
        val primary = TonalPalette.of(seed.hue, seed.chroma)
        val secondary = TonalPalette.of(seed.hue, seed.chroma * 0.5)
        val tertiary = TonalPalette.of((seed.hue + 60.0).mod(360.0), seed.chroma * 0.5)
        val neutral = TonalPalette.of(seed.hue, seed.chroma * 0.04)
        val neutralVariant = TonalPalette.of(seed.hue, seed.chroma * 0.08)
        val error = TonalPalette.of(ERROR_HUE, ERROR_CHROMA)
    }

    /** Full light color scheme for a seed ARGB. */
    fun light(argb: Int): ColorScheme {
        val r = Roles(hctOf(argb))
        return lightColorScheme(
            primary = Color(r.primary.tone(40.0)),
            onPrimary = Color(r.primary.tone(100.0)),
            primaryContainer = Color(r.primary.tone(90.0)),
            onPrimaryContainer = Color(r.primary.tone(10.0)),
            inversePrimary = Color(r.primary.tone(80.0)),
            secondary = Color(r.secondary.tone(40.0)),
            onSecondary = Color(r.secondary.tone(100.0)),
            secondaryContainer = Color(r.secondary.tone(90.0)),
            onSecondaryContainer = Color(r.secondary.tone(10.0)),
            tertiary = Color(r.tertiary.tone(40.0)),
            onTertiary = Color(r.tertiary.tone(100.0)),
            tertiaryContainer = Color(r.tertiary.tone(90.0)),
            onTertiaryContainer = Color(r.tertiary.tone(10.0)),
            error = Color(r.error.tone(40.0)),
            onError = Color(r.error.tone(100.0)),
            errorContainer = Color(r.error.tone(90.0)),
            onErrorContainer = Color(r.error.tone(10.0)),
            background = Color(r.neutral.tone(98.0)),
            onBackground = Color(r.neutral.tone(10.0)),
            surface = Color(r.neutral.tone(98.0)),
            onSurface = Color(r.neutral.tone(10.0)),
            surfaceVariant = Color(r.neutralVariant.tone(90.0)),
            onSurfaceVariant = Color(r.neutralVariant.tone(30.0)),
            inverseSurface = Color(r.neutral.tone(20.0)),
            inverseOnSurface = Color(r.neutral.tone(95.0)),
            outline = Color(r.neutralVariant.tone(50.0)),
            outlineVariant = Color(r.neutralVariant.tone(80.0)),
            scrim = Color(0xFF000000),
            surfaceTint = Color(r.primary.tone(40.0)),
            surfaceContainerLowest = Color(r.neutral.tone(100.0)),
            surfaceContainerLow = Color(r.neutral.tone(96.0)),
            surfaceContainer = Color(r.neutral.tone(94.0)),
            surfaceContainerHigh = Color(r.neutral.tone(92.0)),
            surfaceContainerHighest = Color(r.neutral.tone(90.0)),
        )
    }

    /** Full dark color scheme for a seed ARGB. */
    fun dark(argb: Int): ColorScheme {
        val r = Roles(hctOf(argb))
        return darkColorScheme(
            primary = Color(r.primary.tone(80.0)),
            onPrimary = Color(r.primary.tone(20.0)),
            primaryContainer = Color(r.primary.tone(30.0)),
            onPrimaryContainer = Color(r.primary.tone(90.0)),
            inversePrimary = Color(r.primary.tone(40.0)),
            secondary = Color(r.secondary.tone(80.0)),
            onSecondary = Color(r.secondary.tone(20.0)),
            secondaryContainer = Color(r.secondary.tone(30.0)),
            onSecondaryContainer = Color(r.secondary.tone(90.0)),
            tertiary = Color(r.tertiary.tone(80.0)),
            onTertiary = Color(r.tertiary.tone(20.0)),
            tertiaryContainer = Color(r.tertiary.tone(30.0)),
            onTertiaryContainer = Color(r.tertiary.tone(90.0)),
            error = Color(r.error.tone(80.0)),
            onError = Color(r.error.tone(20.0)),
            errorContainer = Color(r.error.tone(30.0)),
            onErrorContainer = Color(r.error.tone(90.0)),
            background = Color(r.neutral.tone(6.0)),
            onBackground = Color(r.neutral.tone(90.0)),
            surface = Color(r.neutral.tone(6.0)),
            onSurface = Color(r.neutral.tone(90.0)),
            surfaceVariant = Color(r.neutralVariant.tone(30.0)),
            onSurfaceVariant = Color(r.neutralVariant.tone(80.0)),
            inverseSurface = Color(r.neutral.tone(90.0)),
            inverseOnSurface = Color(r.neutral.tone(20.0)),
            outline = Color(r.neutralVariant.tone(60.0)),
            outlineVariant = Color(r.neutralVariant.tone(30.0)),
            scrim = Color(0xFF000000),
            surfaceTint = Color(r.primary.tone(80.0)),
            surfaceContainerLowest = Color(r.neutral.tone(4.0)),
            surfaceContainerLow = Color(r.neutral.tone(10.0)),
            surfaceContainer = Color(r.neutral.tone(12.0)),
            surfaceContainerHigh = Color(r.neutral.tone(17.0)),
            surfaceContainerHighest = Color(r.neutral.tone(22.0)),
        )
    }

    /**
     * AMOLED variant: pure-black surfaces in dark mode (saves OLED power and
     * guarantees maximum contrast for text on any dimmed wallpaper).
     */
    fun darkAmoled(argb: Int): ColorScheme {
        val base = dark(argb)
        return base.copy(
            background = Color(0xFF000000),
            onBackground = Color(0xFFE6E1E9),
            surface = Color(0xFF000000),
            onSurface = Color(0xFFE6E1E9),
            surfaceVariant = Color(0xFF2A272E),
            onSurfaceVariant = Color(0xFFCBC5CF),
            surfaceContainerLowest = Color(0xFF000000),
            surfaceContainerLow = Color(0xFF000000),
            surfaceContainer = Color(0xFF121212),
            surfaceContainerHigh = Color(0xFF1A1A1A),
            surfaceContainerHighest = Color(0xFF222222),
            outlineVariant = Color(0xFF444048),
        )
    }
}
