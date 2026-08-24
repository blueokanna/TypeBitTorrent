package com.typebit.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.typebit.ui.util.Format

/** Download / upload speed pair, qBittorrent style. */
@Composable
fun SpeedPair(
    down: Long,
    up: Long,
    modifier: Modifier = Modifier,
    downColor: Color = Color(0xFF1E88E5),
    upColor: Color = Color(0xFF43A047),
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.ArrowDownward,
            contentDescription = null,
            tint = downColor,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(Format.speed(down), style = MaterialTheme.typography.labelMedium, color = downColor)
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Default.ArrowUpward,
            contentDescription = null,
            tint = upColor,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(Format.speed(up), style = MaterialTheme.typography.labelMedium, color = upColor)
    }
}
