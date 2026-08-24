package com.typebit.platform

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Android: route through the Activity's OnBackPressedDispatcher. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
