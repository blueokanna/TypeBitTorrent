package com.typebit.platform

import androidx.compose.runtime.Composable

/**
 * Registers a system back handler (Android back button / predictive back
 * gesture). The innermost enabled handler wins, so a screen can consume
 * "back" before the route-level handler — e.g. closing a detail page instead
 * of leaving the whole app. On platforms without a system back affordance
 * (desktop) this is a no-op; those screens already expose on-screen back
 * buttons.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
