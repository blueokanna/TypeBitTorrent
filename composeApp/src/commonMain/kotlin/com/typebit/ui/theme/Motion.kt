package com.typebit.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * MD3-Expressive motion: spring physics everywhere — no linear tweens.
 *
 * - `emphasized`: the standard M3 emphasized decelerate/ease, used for
 *   appearance and navigation transitions.
 * - `expressive`: a softer, lower-stiffness spring for content that scales
 *   or fades (icons, cards, selection highlights).
 * - `swift`: a crisp spring for tiny UI elements (checkboxes, toggles).
 */
object TypeBitMotion {
    val emphasized = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    val expressive = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMediumLow,
    )

    val swift = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessHigh,
    )
}
