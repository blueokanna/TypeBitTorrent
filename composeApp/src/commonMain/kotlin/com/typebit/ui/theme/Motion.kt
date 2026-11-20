package com.typebit.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset

/** Shared spring specs for navigation, progress and selection feedback. */
object TypeBitMotion {
    val scheme: MotionScheme = MotionScheme.expressive()
    val emphasized = scheme.defaultEffectsSpec<Float>()
    val expressive = scheme.defaultSpatialSpec<Float>()
    private val displacement = scheme.defaultSpatialSpec<IntOffset>()

    fun containerTransition(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        return (fadeIn(emphasized) + slideInHorizontally(displacement) { direction * it / 12 }) togetherWith
            (fadeOut(emphasized) + slideOutHorizontally(displacement) { -direction * it / 16 })
    }

    val color = scheme.defaultEffectsSpec<Color>()
}
