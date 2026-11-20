package com.typebit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typebit.engine.EngineStatsDto
import com.typebit.ui.util.Format
import com.typebit.store.AppState
import com.typebit.store.AppStore
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsDialog(state: AppState, store: AppStore, onDismiss: () -> Unit) {
    val sample = produceState<EngineStatsDto?>(null, store, state.engineRunning) {
        value = null
        while (state.engineRunning) {
            value = store.fetchStats()
            delay(1000)
        }
    }.value
    var showLogs by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("统计") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                PrimaryTabRow(selectedTabIndex = if (showLogs) 1 else 0) {
                    Tab(selected = !showLogs, onClick = { showLogs = false }, text = { Text("统计") })
                    Tab(selected = showLogs, onClick = { showLogs = true }, text = { Text("引擎日志") })
                }
                if (showLogs) {
                    LazyColumn {
                        if (state.logs.isEmpty()) item { Text("暂无日志", Modifier.padding(8.dp)) }
                        items(state.logs.asReversed()) { line ->
                            Text("[${line.levelName}] ${line.m}", Modifier.padding(vertical = 3.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (sample == null) {
                    Text("等待引擎统计数据…", Modifier.padding(8.dp))
                } else Column(Modifier.verticalScroll(rememberScrollState())) {
                    SectionTitle("网络统计")
                    StatRow("DHT 节点数", state.dhtNodes.toString())
                    StatRow("活跃 Tracker 数", state.trackerCount.toString())
                    if (state.extIp.isNotEmpty()) {
                        StatRow("外网地址", if (state.extPort != 0) "${state.extIp}:${state.extPort}" else state.extIp)
                    }
                    StatRow("LSD 发送 (LAN 广播)", state.lsdSent.toString())
                    StatRow("LSD 接收 (BT-SEARCH)", state.lsdRecv.toString())
                    StatRow("LSD 发现局域网 Peer", state.lsdPeers.toString())
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SectionTitle("本次引擎会话")
                    StatRow("TCP 发送量（含协议）", Format.bytes(sample.u_total))
                    StatRow("TCP 接收量（含协议）", Format.bytes(sample.d_total))
                    StatRow("TCP 收发比（发送 / 接收）", if (sample.d_total > 0) Format.ratio(sample.ratio) else "—")
                    StatRow("本次会话丢弃数据", Format.bytes(sample.d_discarded))
                    StatRow("Peer 连接数", sample.d_peers.toString())
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SectionTitle("缓存统计")
                    StatRow("读缓存命中率（按字节）", sample.readHitRate?.let(Format::percent) ?: "—")
                    StatRow("缓存读取量", Format.bytes(sample.c_read_hits))
                    StatRow("磁盘读取量", Format.bytes(sample.c_read_bytes))
                    StatRow("磁盘读取次数", sample.c_read_ops.toString())
                    StatRow("总缓冲大小", Format.bytes(sample.c_buf))
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SectionTitle("性能统计")
                    StatRow("写缓存使用率", Format.percent(sample.writeCacheUsage))
                    StatRow("读缓存使用率", Format.percent(sample.readCacheUsage))
                    StatRow("待写缓存条目", sample.c_dirty_entries.toString())
                    StatRow("磁盘写入次数", sample.c_write_ops.toString())
                    StatRow("磁盘写入量", Format.bytes(sample.c_write_bytes))
                    StatRow("合并写入量", Format.bytes(sample.c_coalesced))
                    StatRow("合并节省的写入次数", sample.c_ops_saved.toString())
                    StatRow("缓存淘汰次数", sample.c_evictions.toString())
                    sample.dnsSummary?.let {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        SectionTitle("域名解析")
                        StatRow("模式 / 缓存 / 回退", it)
                        sample.dnsProblems?.let { problems -> StatRow("配置问题", problems) }
                    }
                }
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
            value, Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
