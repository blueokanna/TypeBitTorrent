package com.typebit.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Android has no visible horizontal scrollbar — touch drag scrolls. */
@Composable
actual fun HorizontalTableScrollbar(
    hState: ScrollState,
    modifier: Modifier,
) {
}
