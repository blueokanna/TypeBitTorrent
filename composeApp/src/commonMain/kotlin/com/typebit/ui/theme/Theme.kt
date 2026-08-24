package com.typebit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.typebit.ui.monet.DynamicScheme
import com.typebit.ui.wallpaper.WallpaperLayer

/**
 * Semantic status colors used by progress bars, badges and speed chips.
 * Deliberately NOT derived from the dynamic palette — status must stay
 * recognizable in both light and dark, on any wallpaper.
 */
@Immutable
object StatusColors {
    val Download = Color(0xFF1E88E5)
    val Seed = Color(0xFF43A047)
    val Pause = Color(0xFFFFB300)
    val Error = Color(0xFFE53935)
    val Idle = Color(0xFF9E9E9E)
    val Metadata = Color(0xFF8E24AA)
}

/**
 * App-wide semantic colors. Components read [LocalStatusColors] so they can
 * be themed once; nothing hard-codes ARGB in the UI layer anymore.
 */
@Immutable
data class StatusColorSet(
    val download: Color = StatusColors.Download,
    val seed: Color = StatusColors.Seed,
    val pause: Color = StatusColors.Pause,
    val error: Color = StatusColors.Error,
    val idle: Color = StatusColors.Idle,
    val metadata: Color = StatusColors.Metadata,
)

val LocalStatusColors = staticCompositionLocalOf { StatusColorSet() }

/** Re-exported accessor for ergonomics. */
object TypeBitThemeColors {
    val status: StatusColorSet @Composable get() = LocalStatusColors.current
    val colorScheme: ColorScheme @Composable get() = MaterialTheme.colorScheme
}

/**
 * The single theme entry point.
 *
 * Builds the Material-You (Monet) [ColorScheme] from a seed ARGB via the
 * pure-Kotlin HCT/CAM16 engine, then lays the whole app over the optional
 * wallpaper (gaussian blur + DIM + readability scrim) so text stays legible
 * on any image. AMOLED mode pins dark surfaces to pure black.
 *
 * Light mode uses a white scrim and dark on-surface roles (black text);
 * dark/AMOLED use a black scrim and light on-surface roles (white text).
 * The scrim strength and blur radius come from settings, so the preview in
 * the settings screen shows exactly what the app will look like.
 */
@Composable
fun TypeBitTheme(
    seedArgb: Int,
    darkTheme: Boolean,
    amoled: Boolean = false,
    /** Pre-blurred wallpaper bitmap (see [com.typebit.ui.wallpaper.blurWallpaper]). */
    wallpaper: ImageBitmap? = null,
    wallpaperEnabled: Boolean = false,
    wallpaperDim: Float = 0.45f,
    wallpaperFit: Boolean = false,
    wallpaperOffsetY: Float = 0f,
    /** Average wallpaper luminance 0..1 (drives auto-contrast scrim). */
    wallpaperBrightness: Float = 0.5f,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(seedArgb, darkTheme, amoled, wallpaperEnabled) {
        val base = when {
            darkTheme && amoled -> DynamicScheme.darkAmoled(seedArgb)
            darkTheme -> DynamicScheme.dark(seedArgb)
            else -> DynamicScheme.light(seedArgb)
        }
        if (wallpaperEnabled) base.withTranslucentSurfaces() else base
    }

    val effectiveDim = remember(wallpaperEnabled, wallpaperDim, wallpaperBrightness, darkTheme) {
        if (!wallpaperEnabled) {
            wallpaperDim.coerceIn(0f, 0.85f)
        } else {
            val base = wallpaperDim.coerceIn(0f, 0.85f)
            val boost = if (darkTheme) {
                ((wallpaperBrightness - 0.5f) * 0.9f).coerceAtLeast(0f)
            } else {
                ((0.5f - wallpaperBrightness) * 0.9f).coerceAtLeast(0f)
            }
            (base + boost).coerceIn(0.35f, 0.88f)
        }
    }

    val status = if (darkTheme) {
        StatusColorSet(
            download = Color(0xFF64B5F6),
            seed = Color(0xFF81C784),
            pause = Color(0xFFFFCA28),
            error = Color(0xFFEF5350),
            idle = Color(0xFFBDBDBD),
            metadata = Color(0xFFCE93D8),
        )
    } else {
        StatusColorSet()
    }

    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typebitTypography(),
            shapes = TypeBitShapes,
        ) {
            WallpaperLayer(
                bitmap = if (wallpaperEnabled) wallpaper else null,
                dimAmount = effectiveDim,
                scrimColor = if (darkTheme) Color.Black else Color.White,
                backgroundColor = colorScheme.background,
                contentScale = if (wallpaperFit) ContentScale.Fit else ContentScale.Crop,
                verticalOffsetRatio = wallpaperOffsetY,
            ) {
                content()
            }
        }
    }
}

/**
 * Wallpaper handling:
 * - `background` stays SEMI-transparent so the wallpaper is clearly visible
 *   in the empty background (this is the whole point of a wallpaper).
 * - Every component surface (cards, fields, bars) is essentially OPAQUE
 *   (0.98) so text stays crisp and wallpaper watermarks never bleed through
 *   controls — that was the "elements look overlapped" bug.
 */
private fun ColorScheme.withTranslucentSurfaces(): ColorScheme = copy(
    background = background.copy(alpha = 0.90f),
    surface = surface.copy(alpha = 0.98f),
    surfaceContainerLowest = surfaceContainerLowest.copy(alpha = 0.98f),
    surfaceContainerLow = surfaceContainerLow.copy(alpha = 0.98f),
    surfaceContainer = surfaceContainer.copy(alpha = 0.98f),
    surfaceContainerHigh = surfaceContainerHigh.copy(alpha = 0.98f),
    surfaceContainerHighest = surfaceContainerHighest.copy(alpha = 0.98f),
    surfaceVariant = surfaceVariant.copy(alpha = 0.98f),
)
