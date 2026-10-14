package com.typebit.ui.components

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// A draggable scrollbar themed against Material: the default skiko thumb is effectively invisible
// on a light surface.
@Composable
actual fun HorizontalTableScrollbar(
    hState: ScrollState,
    modifier: Modifier,
) {
    HorizontalScrollbar(
        adapter = rememberScrollbarAdapter(hState),
        style =
            ScrollbarStyle(
                minimalHeight = 20.dp,
                thickness = 12.dp,
                shape = RoundedCornerShape(6.dp),
                hoverDurationMillis = 300,
                unhoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                hoverColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        modifier = modifier.height(12.dp),
    )
}

