package com.typebit.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Compose skiko scrollbar API is desktop-only, so this expect/actual keeps the shared
 * table compiling: desktop draws a real draggable scrollbar, others render nothing.
 */
@Composable
expect fun HorizontalTableScrollbar(
    hState: ScrollState,
    modifier: Modifier = Modifier,
)
