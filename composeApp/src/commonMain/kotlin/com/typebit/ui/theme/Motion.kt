package com.typebit.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset

/** Shared spring specs for navigation, progress and selection feedback. */
object TypeBitMotion {
    val emphasized = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    val expressive = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMediumLow,
    )

    private val displacement = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 550f)

    fun containerTransition(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        return (fadeIn(emphasized) + slideInHorizontally(displacement) { direction * it / 12 }) togetherWith
            (fadeOut(emphasized) + slideOutHorizontally(displacement) { -direction * it / 16 })
    }

    val color = spring<Color>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
