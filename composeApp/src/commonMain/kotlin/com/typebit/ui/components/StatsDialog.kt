package com.typebit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typebit.engine.EngineStatsDto
import com.typebit.ui.util.Format

/**
 * qBittorrent-style statistics dialog. Every row is backed by a real engine
 * counter (see [EngineStatsDto]); nothing is fabricated. The caller polls
 * [EngineStatsDto] while the dialog is open and passes the live network
 * counters ([dhtNodes], [trackerCount], [extIp]/[extPort]) so the DHT and
 * tracker status the user sees is always current.
 */
@Composable
fun StatsDialog(
    stats: EngineStatsDto,
    onDismiss: () -> Unit,
    dhtNodes: Int = 0,
    trackerCount: Int = 0,
    extIp: String = "",
    extPort: Int = 0,
    lsdSent: Long = 0,
    lsdRecv: Long = 0,
    lsdPeers: Long = 0,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("统计") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                SectionTitle("网络统计")
                StatRow("DHT 节点数", dhtNodes.toString())
                StatRow("活跃 Tracker 数", trackerCount.toString())
                if (extIp.isNotEmpty()) {
                    StatRow(
                        "外网地址",
                        if (extPort != 0) "$extIp:$extPort" else extIp,
                    )
                }
                // LSD (BEP-14) LAN discovery — visible proof it is alive:
                // announces sent, BT-SEARCH datagrams received, LAN peers
                // discovered. All three at 0 = the LAN multicast is blocked
                // (router AP isolation / firewall / missing multicast lock).
                StatRow("LSD 发送 (LAN 广播)", lsdSent.toString())
                StatRow("LSD 接收 (BT-SEARCH)", lsdRecv.toString())
                StatRow("LSD 发现局域网 Peer", lsdPeers.toString())
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("用户统计")
                StatRow("全局上传", Format.bytes(stats.u_total))
                StatRow("总计下载", Format.bytes(stats.d_total))
                StatRow("全局分享率", Format.ratio(stats.ratio))
                StatRow("本次会话丢弃数据", Format.bytes(stats.d_discarded))
                StatRow("已连接的用户数", stats.d_peers.toString())
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("缓存统计")
                StatRow("读缓存命中率", Format.percent(stats.readHitRate))
                StatRow("读缓存次数", "${stats.c_read_hits} / ${stats.c_read_ops}")
                StatRow("总缓冲大小", Format.bytes(stats.c_buf))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("性能统计")
                StatRow("写入缓存超负荷", Format.percent(stats.writeOverload))
                StatRow("读取缓存超负荷", Format.percent(stats.readOverload))
                StatRow("等待落盘的 I/O 任务", stats.c_dirty_entries.toString())
                StatRow("磁盘写入次数", stats.c_write_ops.toString())
                StatRow("磁盘写入量", Format.bytes(stats.c_write_bytes))
                StatRow("合并节约的写入", Format.bytes(stats.c_coalesced))
                StatRow("磁盘读取量", Format.bytes(stats.c_read_bytes))
                StatRow("缓存淘汰次数", stats.c_evictions.toString())
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        },
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
