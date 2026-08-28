package com.typebit.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typebit.model.Torrent
import com.typebit.model.TorrentStatus
import com.typebit.platform.formatDateTime
import com.typebit.ui.components.LabelValueRow
import com.typebit.ui.util.Format

/** 信息 tab — transfer + general sections (qBittorrent/BitComet style). */
@Composable
fun InfoTab(torrent: Torrent, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        SectionTitle("传输")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabelValueRow("耗时", durationSince(torrent.addedAt), Modifier.weight(1f))
            LabelValueRow("剩余时间", Format.eta(torrent.etaSeconds), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabelValueRow("已下载", Format.bytes(torrent.downloadedBytes), Modifier.weight(1f))
            LabelValueRow("已上传", Format.bytes(torrent.uploadedBytes), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabelValueRow("下载速率", Format.speed(torrent.downSpeed), Modifier.weight(1f))
            LabelValueRow("上传速率", Format.speed(torrent.upSpeed), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabelValueRow("分享率", Format.ratio(torrent.ratio), Modifier.weight(1f))
            LabelValueRow("分块", "${torrent.havePieces}/${torrent.pieceCount}", Modifier.weight(1f))
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionTitle("常规")
        LabelValueRow("名称", torrent.name)
        LabelValueRow("保存路径", torrent.saveDir)
        if (torrent.status != TorrentStatus.SEEDING) {
            // 下载未完成：引擎写入 <name>.part，全部校验通过后才重命名为最终名。
            LabelValueRow("暂存方式", "未完成，文件暂存为 .part，校验通过后自动重命名")
        }
        LabelValueRow("总大小", Format.bytesDetailed(torrent.sizeBytes))
        LabelValueRow("剩余大小", Format.bytes(torrent.remainingBytes))
        LabelValueRow("信息哈希", torrent.hash)
        LabelValueRow("类型", torrent.kind)
        LabelValueRow("私有种子", if (torrent.isPrivate) "是" else "否")
        LabelValueRow("添加时间", formatDateTime(torrent.addedAt))
        if (torrent.createdAt != null) {
            LabelValueRow("创建时间", formatDateTime(torrent.createdAt))
        }
        if (torrent.createdBy != null) {
            LabelValueRow("创建者", torrent.createdBy)
        }
        if (torrent.comment != null) {
            LabelValueRow("备注", torrent.comment)
        }
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Spacer(Modifier.height(2.dp))
}

private fun durationSince(epochMillis: Long): String {
    val sec = (System.currentTimeMillis() - epochMillis).coerceAtLeast(0) / 1000
    return Format.eta(sec) ?: "0 秒"
}
