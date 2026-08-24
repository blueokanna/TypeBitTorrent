package com.typebit.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.ui.components.EmptyState
import com.typebit.ui.theme.TypeBitThemeColors

/**
 * 分块 tab — a heat-grid of every piece. Colors come from the REAL verified
 * bitfield (`Torrent.haveBitsHex`, reported by the engine); nothing is
 * inferred. Blue = verified, green = current progress fills from events.
 */
@Composable
fun PiecesTab(torrent: Torrent, modifier: Modifier = Modifier) {
    val pieceCount = torrent.pieceCount
    if (pieceCount <= 0) {
        EmptyState(
            title = "无分块信息",
            subtitle = if (torrent.metadataReady) "该种子没有分块数据" else "元数据未获取",
        )
        return
    }

    val bits = decodeHex(torrent.haveBitsHex)
    val have = List(pieceCount) { i -> isHave(bits, i) }
    val haveCount = have.count { it }
    val s = TypeBitThemeColors.status
    // Live progress estimate merges event-delivered piece count with the
    // verified bitfield — the max is never above pieceCount.
    val effectiveHave = (haveCount.coerceAtLeast(torrent.havePieces)).coerceAtMost(pieceCount)

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "已完成 $effectiveHave / $pieceCount 分块 (${(effectiveHave * 100 / pieceCount)}%)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(s.seed)
            Spacer(Modifier.width(4.dp))
            Text("已验证", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            LegendDot(s.download)
            Spacer(Modifier.width(4.dp))
            Text("待下载", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(14.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pieceCount) { i ->
                PieceCell(has = have[i])
            }
        }
    }
}

@Composable
private fun PieceCell(has: Boolean) {
    val s = TypeBitThemeColors.status
    Box(
        Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (has) s.seed else s.download.copy(alpha = 0.25f)),
    )
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/** MSB-first hex bitfield → per-piece booleans. */
private fun decodeHex(hex: String): ByteArray {
    if (hex.isEmpty()) return ByteArray(0)
    val out = ArrayList<Byte>(hex.length / 2)
    var i = 0
    while (i + 1 < hex.length) {
        val hi = hex[i].digitToIntOrNull(16) ?: 0
        val lo = hex[i + 1].digitToIntOrNull(16) ?: 0
        out.add(((hi shl 4) or lo).toByte())
        i += 2
    }
    return out.toByteArray()
}

private fun isHave(bits: ByteArray, piece: Int): Boolean {
    val byte = piece / 8
    if (byte >= bits.size) return false
    val bit = 7 - (piece % 8)
    return (bits[byte].toInt() and (1 shl bit)) != 0
}
