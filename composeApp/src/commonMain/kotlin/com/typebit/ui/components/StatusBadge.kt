package com.typebit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.typebit.model.TorrentStatus
import com.typebit.ui.i18n.LocalStrings
import com.typebit.ui.i18n.TextKey
import com.typebit.ui.theme.TypeBitThemeColors

/** Fully-rounded MD3 status pill: tinted container + colored label. */
@Composable
fun StatusBadge(status: TorrentStatus, modifier: Modifier = Modifier) {
    val s = TypeBitThemeColors.status
    val strings = LocalStrings.current
    val (label, color) = when (status) {
        TorrentStatus.DOWNLOADING -> strings[TextKey.STATUS_DOWNLOADING] to s.download
        TorrentStatus.SEEDING -> strings[TextKey.STATUS_SEEDING] to s.seed
        TorrentStatus.PAUSED -> strings[TextKey.STATUS_PAUSED] to s.pause
        TorrentStatus.FETCHING_METADATA -> strings[TextKey.STATUS_FETCHING_METADATA] to s.metadata
        TorrentStatus.STOPPED -> strings[TextKey.STATUS_STOPPED] to s.idle
        TorrentStatus.FAILED -> strings[TextKey.STATUS_FAILED] to s.error
    }
    val bg = color.copy(alpha = 0.16f)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
