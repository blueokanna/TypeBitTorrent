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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typebit.data.ReceiptFile
import com.typebit.engine.ReceiptVerifyResultDto
import com.typebit.model.Torrent
import com.typebit.store.AppStore
import com.typebit.ui.components.LabelValueRow
import com.typebit.ui.util.Format
import kotlinx.coroutines.launch

/**
 * 回执 tab — proof-of-download receipts for this torrent.
 *
 * A receipt is an Ed25519-signed attestation binding (content root, byte
 * range, wall-clock window, bytes received, challenge commitment, data
 * proof over real sampled blocks). Only bytes the engine actually verified
 * can be covered — exporting is impossible below 90% coverage, so a receipt
 * is evidence of a real download, not a claim.
 *
 * Per torrent you can:
 * - export a signed receipt (saved to `<appData>/receipts/`),
 * - re-verify any saved receipt,
 * - verify a receipt JSON from anyone else (paste it here).
 */
@Composable
fun ReceiptsTab(torrent: Torrent, store: AppStore, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<List<ReceiptFile>>(emptyList()) }
    var busyPath by remember { mutableStateOf<String?>(null) }
    var verifyMessage by remember { mutableStateOf<String?>(null) }
    var foreignJson by remember { mutableStateOf("") }
    var foreignResult by remember { mutableStateOf<ReceiptVerifyResultDto?>(null) }

    fun refresh() {
        saved = store.listReceiptsFor(torrent.hash)
    }

    LaunchedEffect(torrent.hash) {
        refresh()
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        SectionTitle("签名回执（Proof-of-Download）")
        LabelValueRow("内容根", torrent.hash.take(40) + "…")
        LabelValueRow(
            "已校验覆盖",
            "${Format.bytes(torrent.downloadedBytes)} / ${Format.bytes(torrent.sizeBytes)}",
        )
        Text(
            "回执是节点用 Ed25519 私钥对（内容哈希 · 字节区间 · 时间窗 · 已收字节 · 数据证明）的签名。" +
                "只有引擎真正校验过、且覆盖 ≥90% 的区间才能签发——无法伪造。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = torrent.downloadedBytes > 0 && !exporting,
                onClick = {
                    exporting = true
                    exportMessage = null
                    scope.launch {
                        val r = store.exportReceipt(
                            hash = torrent.hash,
                            downloadedBytes = torrent.downloadedBytes,
                            addedAtMs = torrent.addedAt,
                        )
                        exportMessage =
                            if (r.isSuccess) {
                                "已导出：${r.path}\n覆盖 ${r.receipt?.coverageRatio?.let { (it * 100).toInt() }}% · 节点 ${r.receipt?.node_id?.take(16)}…"
                            } else {
                                "导出失败：${r.error ?: "未知错误"}"
                            }
                        exporting = false
                        refresh()
                    }
                },
            ) {
                Text(if (exporting) "导出中…" else "导出签名回执")
            }
            if (exporting) {
                Spacer(Modifier.height(0.dp))
                CircularProgressIndicator(
                    Modifier.padding(start = 12.dp).height(20.dp).fillMaxWidth(0.05f),
                    strokeWidth = 2.dp,
                )
            }
        }
        exportMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("已导出")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SectionTitle("本种子的回执（${saved.size}）")
        if (saved.isEmpty()) {
            Text(
                "还没有导出的回执。下载完成后点上面的按钮即可生成并保存。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        saved.forEach { file ->
            ReceiptRow(
                file = file,
                busy = busyPath == file.path,
                onVerify = {
                    busyPath = file.path
                    verifyMessage = null
                    scope.launch {
                        val text = store.readReceipt(file.path)
                        verifyMessage =
                            if (text == null) {
                                "文件不存在"
                            } else {
                                val r = store.verifyReceiptJson(text)
                                renderVerify(r)
                            }
                        busyPath = null
                    }
                },
                onDelete = {
                    store.deleteReceipt(file.path)
                    refresh()
                },
            )
        }
        verifyMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SectionTitle("校验他人回执")
        OutlinedTextField(
            value = foreignJson,
            onValueChange = { foreignJson = it },
            modifier = Modifier.fillMaxWidth().height(96.dp),
            placeholder = { Text("粘贴任意回执 JSON…") },
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            enabled = foreignJson.isNotBlank(),
            onClick = {
                foreignResult = null
                scope.launch { foreignResult = store.verifyReceiptJson(foreignJson) }
            },
        ) {
            Text("校验")
        }
        foreignResult?.let { r ->
            Text(
                if (r.ok) {
                    "✅ 签名有效 — 节点 ${r.node_id.take(16)}… · 区间 [${Format.bytes(r.range_start)}, ${Format.bytes(r.range_end)}) · " +
                        "已收 ${Format.bytes(r.bytes_received)} · 窗口 ${r.epoch_start}–${r.epoch_end}"
                } else {
                    "❌ 校验失败：${r.error ?: "签名或结构无效"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ReceiptRow(
    file: ReceiptFile,
    busy: Boolean,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    val receipt = file.receipt
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    "节点 ${receipt?.node_id?.take(16) ?: "（无法解析）"}…",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                if (receipt != null) {
                    Text(
                        "区间 [${Format.bytes(receipt.range_start)}, ${Format.bytes(receipt.range_end)}) · " +
                            "已收 ${Format.bytes(receipt.bytes_received)} · " +
                            "覆盖 ${(receipt.coverageRatio * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            Row {
                if (busy) {
                    CircularProgressIndicator(Modifier.height(18.dp).fillMaxWidth(0.05f), strokeWidth = 2.dp)
                } else {
                    OutlinedButton(onClick = onVerify, modifier = Modifier.padding(end = 4.dp)) {
                        Text("校验")
                    }
                }
                IconButton(onClick = onDelete) {
                    Text("删除")
                }
            }
        }
    }
}

private fun renderVerify(r: ReceiptVerifyResultDto): String =
    if (r.ok) {
        "✅ 签名有效 — 节点 ${r.node_id.take(16)}… · 区间 [${Format.bytes(r.range_start)}, ${Format.bytes(r.range_end)}) · " +
            "已收 ${Format.bytes(r.bytes_received)} · 窗口 ${r.epoch_start}–${r.epoch_end}"
    } else {
        "❌ 校验失败：${r.error ?: "签名或结构无效"}"
    }
