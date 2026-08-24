package com.typebit.platform

import androidx.compose.runtime.Composable

/** Desktop has no system back gesture; on-screen back buttons are used. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
