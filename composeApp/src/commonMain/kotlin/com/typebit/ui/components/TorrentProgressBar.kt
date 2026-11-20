package com.typebit.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.typebit.ui.theme.TypeBitMotion
import com.typebit.model.TorrentStatus
import com.typebit.ui.theme.TypeBitThemeColors

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

@Composable
fun TorrentProgressBar(
    progress: Float,
    status: TorrentStatus,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), TypeBitMotion.emphasized, label = "progress")
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier.fillMaxWidth(),
        color = progressColor(status),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}
