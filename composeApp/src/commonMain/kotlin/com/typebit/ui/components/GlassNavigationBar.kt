package com.typebit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import com.typebit.data.AppearanceSettings
import com.typebit.data.NavigationGlass

@Composable
internal fun GlassNavigationBar(
    backdrop: HazeState,
    appearance: AppearanceSettings,
    content: @Composable RowScope.() -> Unit,
) {
    val mode = appearance.navigationGlass
    val radius = appearance.navigationBlurDp.coerceIn(0f, 32f).dp
    val colors = MaterialTheme.colorScheme
    val input = remember(backdrop) { HazeInput.Sources(backdrop) }
    val surface = colors.surfaceContainerHigh
    val glass = when (mode) {
        NavigationGlass.LIQUID -> Modifier.hazeGlass(input, remember(radius, surface) {
            GlassStyle {
                shape(CircleShape)
                optics { copy(blurRadius = OpticalSizeValue.Fixed(radius)) }
                tint(surface.copy(alpha = 0.52f))
            }
        })
        else -> Modifier.hazeBlur(input, remember(radius, surface) {
            HazeBlurStyle {
                blurRadius(radius)
                backgroundColor(surface)
                noiseFactor(0f)
            }
        })
    }
    val tint = when (mode) {
        NavigationGlass.AERO -> Modifier.background(Brush.verticalGradient(listOf(
            colors.surfaceBright.copy(alpha = 0.82f), surface.copy(alpha = 0.45f), surface.copy(alpha = 0.72f),
        )))
        NavigationGlass.LIQUID -> Modifier
        else -> Modifier.background(surface.copy(alpha = 0.64f))
    }
    Box(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        NavigationBar(
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(CircleShape).then(glass)
                .then(tint).border(1.dp, colors.outline, CircleShape),
            containerColor = Color.Transparent,
            windowInsets = WindowInsets(0),
            content = content,
        )
    }
}
