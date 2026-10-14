package com.typebit.platform

import androidx.compose.runtime.Composable

// Registers a system back handler (the innermost enabled handler wins); a no-op on desktop, where screens
// expose on-screen back buttons.
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
