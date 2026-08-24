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
    wallpaper: ImageBitmap? = null,
    wallpaperEnabled: Boolean = false,
    wallpaperDim: Float = 0.45f,
    wallpaperBlurPx: Float = 24f,
    wallpaperFit: Boolean = false,
    wallpaperOffsetY: Float = 0f,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(seedArgb, darkTheme, amoled, wallpaperEnabled) {
        val base = when {
            darkTheme && amoled -> DynamicScheme.darkAmoled(seedArgb)
            darkTheme -> DynamicScheme.dark(seedArgb)
            else -> DynamicScheme.light(seedArgb)
        }
        // With a wallpaper behind the UI the surfaces become translucent so
        // the image glows through; text roles stay opaque and keep contrast.
        if (wallpaperEnabled) base.withTranslucentSurfaces() else base
    }

    // Slightly brighter status hues on dark surfaces for contrast.
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
            typography = TypeBitTypography,
            shapes = TypeBitShapes,
        ) {
            WallpaperLayer(
                bitmap = if (wallpaperEnabled) wallpaper else null,
                dimAmount = wallpaperDim,
                blurRadiusPx = wallpaperBlurPx,
                // Light theme: white scrim keeps black text readable on a
                // bright wallpaper. Dark theme: black scrim (AMOLED-like).
                scrimColor = if (darkTheme) Color.Black else Color.White,
                // The theme background is painted explicitly so the whole
                // window follows the scheme (AMOLED → pure black). Desktop
                // windows are transparent by default.
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
 * With the wallpaper enabled every surface becomes translucent so the image
 * (already dimmed + blurred) shows through. The translucency is conservative
 * — text contrast is preserved by the scrim, not by chance.
 */
private fun ColorScheme.withTranslucentSurfaces(): ColorScheme = copy(
    background = background.copy(alpha = 0.90f),
    surface = surface.copy(alpha = 0.86f),
    surfaceContainerLowest = surfaceContainerLowest.copy(alpha = 0.90f),
    surfaceContainerLow = surfaceContainerLow.copy(alpha = 0.84f),
    surfaceContainer = surfaceContainer.copy(alpha = 0.84f),
    surfaceContainerHigh = surfaceContainerHigh.copy(alpha = 0.86f),
    surfaceContainerHighest = surfaceContainerHighest.copy(alpha = 0.88f),
    surfaceVariant = surfaceVariant.copy(alpha = 0.84f),
)
