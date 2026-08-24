package com.typebit.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.typebit.model.TorrentStatus
import com.typebit.ui.theme.TypeBitThemeColors

/** The accent color of a progress bar depends on torrent status. */
@Composable
fun progressColor(status: TorrentStatus): Color {
    val s = TypeBitThemeColors.status
    return when (status) {
        TorrentStatus.DOWNLOADING -> s.download
        TorrentStatus.SEEDING -> s.seed
        TorrentStatus.PAUSED -> s.pause
        TorrentStatus.FETCHING_METADATA -> s.metadata
        TorrentStatus.STOPPED -> s.idle
        TorrentStatus.FAILED -> s.error
    }
}

/** Thin M3 progress bar with status-aware color. */
@Composable
fun TorrentProgressBar(
    progress: Float,
    status: TorrentStatus,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "progress")
    val color = progressColor(status)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated.coerceAtLeast(0f))
                .height(6.dp)
                .background(color),
        )
    }
}
