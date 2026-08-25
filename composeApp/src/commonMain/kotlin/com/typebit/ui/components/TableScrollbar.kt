package com.typebit.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A visible horizontal scrollbar for a horizontally scrolling table.
 *
 * The Compose 1.8 skiko `HorizontalScrollbar` / `rememberScrollbarAdapter`
 * API is desktop-only (not exposed to commonMain), so this is a KMP
 * expect/actual: desktop renders a real draggable scrollbar, other
 * platforms render nothing (touch/mouse-wheel scrolling still works).
 */
@Composable
expect fun HorizontalTableScrollbar(
    hState: ScrollState,
    modifier: Modifier = Modifier,
)
