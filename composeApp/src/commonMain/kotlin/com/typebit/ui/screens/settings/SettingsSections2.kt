package com.typebit.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typebit.data.AdvancedSettings
import com.typebit.data.AppSettings
import com.typebit.data.BitTorrentSettings
import com.typebit.data.ContentLayout
import com.typebit.data.EncryptionMode
import com.typebit.data.RssSettings
import com.typebit.data.UtpMixedMode
import com.typebit.data.WebUiSettings
import com.typebit.platform.fetchUrlText
import com.typebit.util.TrackerListParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// BitTorrent
// ---------------------------------------------------------------------------

/**
 * Import a tracker list from a URL (e.g. an `ngosang/trackerslist` raw endpoint). Fetches, parses
 * (plain/JSON/HTML), de-duplicates and appends the results to the extra-trackers field.
 */
@Composable
private fun TrackerImportRow(
        s: BitTorrentSettings,
        update: (BitTorrentSettings) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        SettingTextField(
                label = "从 URL 导入 Tracker",
                value = url,
                onValueChange = { url = it },
                placeholder = "https://…/trackers_all.txt",
                modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
                enabled = url.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    status = ""
                    scope.launch {
                        val body = withContext(Dispatchers.IO) { fetchUrlText(url.trim(), 15_000) }
                        val imported = body?.let { TrackerListParser.parse(it) }.orEmpty()
                        if (imported.isEmpty()) {
                            status = "导入失败：无法解析任何 tracker"
                        } else {
                            val existing =
                                    s.extraTrackers
                                            .lineSequence()
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                            .toMutableSet()
                            var added = 0
                            for (t in imported) if (existing.add(t)) added++
                            update(s.copy(extraTrackers = existing.joinToString("\n")))
                            status = "导入 $added 个 tracker（共 ${existing.size}）"
                        }
                        busy = false
                    }
                },
        ) { Text(if (busy) "导入中…" else "导入") }
    }
    if (status.isNotEmpty()) {
        Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color =
                        if (status.startsWith("导入失败")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
fun BitTorrentSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val s = settings.bitTorrent
    val update: (BitTorrentSettings) -> Unit = { ns -> onChange(settings.copy(bitTorrent = ns)) }

    SectionCard("对等网络") {
        SettingSwitch("启用 DHT", "分布式哈希表（重启引擎后生效）", s.enableDht, { update(s.copy(enableDht = it)) })
        SettingSwitch("启用 PEX", "Peer 交换（BEP-11）", s.enablePex, { update(s.copy(enablePex = it)) })
        SettingSwitch(
                "启用本地对等发现 (LSD)",
                "局域网内发现对等（存储项）",
                s.enableLsd,
                { update(s.copy(enableLsd = it)) }
        )
        SettingSwitch(
                "启用 UPnP / NAT-PMP",
                "自动端口映射（存储项）",
                s.enableUpnp || s.enableNatPmp,
                { update(s.copy(enableUpnp = it, enableNatPmp = it)) }
        )
        SettingSwitch("匿名模式", "尽量隐藏客户端特征", s.anonymousMode, { update(s.copy(anonymousMode = it)) })
        SettingSwitch(
                "反吸血检测",
                "识别迅雷等吸血客户端并上报统计（引擎 0.1.0 无拒绝 API，见 README）",
                s.antiLeechEnabled,
                { update(s.copy(antiLeechEnabled = it)) },
        )
    }

    SectionCard("加密") {
        SettingDropdown(
                label = "加密模式",
                options = EncryptionMode.entries,
                selected = s.encryptionMode,
                onSelect = { update(s.copy(encryptionMode = it)) },
                labelOf = {
                    when (it) {
                        EncryptionMode.ALLOW -> "允许加密"
                        EncryptionMode.PREFER -> "优先加密"
                        EncryptionMode.REQUIRE -> "强制加密"
                    }
                },
        )
        Text(
                "注意：typebit 0.1.0 的 wire 协议为明文实现，此选项为兼容 qBittorrent 的设置项（存储项）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard("种子") {
        SettingNumberField(
                "每种子最大 Peers",
                s.maxPeersPerTorrent.toString(),
                { update(s.copy(maxPeersPerTorrent = it.toIntOrNull() ?: s.maxPeersPerTorrent)) }
        )
        SettingNumberField(
                "请求流水线",
                s.requestPipeline.toString(),
                { update(s.copy(requestPipeline = it.toIntOrNull() ?: s.requestPipeline)) }
        )
        SettingNumberField(
                "Endgame 触发分块数",
                s.endgamePieces.toString(),
                { update(s.copy(endgamePieces = it.toIntOrNull() ?: s.endgamePieces)) }
        )
        SettingSwitch(
                "智能分块调度",
                "视频类内容优先下载首尾分块（可先播放）",
                s.smartScheduling,
                { update(s.copy(smartScheduling = it)) }
        )
        SettingSwitch(
                "使用内置默认 Tracker",
                "种子未声明 Tracker 时使用默认列表",
                s.useDefaultTrackers,
                { update(s.copy(useDefaultTrackers = it)) }
        )
        SettingTextField(
                "额外 Tracker",
                s.extraTrackers,
                { update(s.copy(extraTrackers = it)) },
                placeholder = "每行一个 announce URL"
        )
        TrackerImportRow(s, update)
        SettingNumberField(
                "磁盘缓存 (MiB)",
                (s.cacheBytes / 1024 / 1024).toString(),
                { update(s.copy(cacheBytes = (it.toLongOrNull() ?: 256L) * 1024 * 1024)) },
                suffix = "MiB"
        )
    }

    SectionCard("种子调度器 (typebit)") {
        SettingNumberField(
                "做种槽位",
                s.seedingSlots.toString(),
                { update(s.copy(seedingSlots = it.toIntOrNull() ?: s.seedingSlots)) }
        )
        SettingNumberField(
                "下载槽位",
                s.leechingSlots.toString(),
                { update(s.copy(leechingSlots = it.toIntOrNull() ?: s.leechingSlots)) }
        )
        SettingNumberField(
                "乐观未阻塞间隔 (ms)",
                s.optimisticIntervalMs.toString(),
                {
                    update(
                            s.copy(
                                    optimisticIntervalMs = it.toLongOrNull()
                                                    ?: s.optimisticIntervalMs
                            )
                    )
                }
        )
        SettingNumberField(
                "快照超时 (ms)",
                s.snubTimeoutMs.toString(),
                { update(s.copy(snubTimeoutMs = it.toLongOrNull() ?: s.snubTimeoutMs)) }
        )
        SettingNumberField(
                "调度权重 α",
                s.schedulerAlpha.toString(),
                { update(s.copy(schedulerAlpha = it.toIntOrNull() ?: s.schedulerAlpha)) }
        )
        SettingNumberField(
                "调度权重 β",
                s.schedulerBeta.toString(),
                { update(s.copy(schedulerBeta = it.toIntOrNull() ?: s.schedulerBeta)) }
        )
        SettingNumberField(
                "调度权重 γ",
                s.schedulerGamma.toString(),
                { update(s.copy(schedulerGamma = it.toIntOrNull() ?: s.schedulerGamma)) }
        )
        SettingNumberField(
                "调度权重 δ",
                s.schedulerDelta.toString(),
                { update(s.copy(schedulerDelta = it.toIntOrNull() ?: s.schedulerDelta)) }
        )
    }

    SectionCard("文件布局") {
        SettingDropdown(
                label = "内容布局",
                options = ContentLayout.entries,
                selected = s.contentLayout,
                onSelect = { update(s.copy(contentLayout = it)) },
                labelOf = {
                    when (it) {
                        ContentLayout.ORIGINAL -> "原始"
                        ContentLayout.SUBFOLDER -> "创建子文件夹"
                        ContentLayout.NO_SUBFOLDER -> "不使用子文件夹"
                    }
                },
        )
    }
}

// ---------------------------------------------------------------------------
// Background (Android foreground service + battery optimization)
// ---------------------------------------------------------------------------

/**
 * Background-transfer controls. Android keeps a `dataSync` foreground service + partial wake lock
 * running while any torrent is active, so downloads continue with the screen locked or the app
 * backgrounded. The battery-optimization exemption is the one piece a user must grant through the
 * system dialog (apps cannot self-exempt) — this card surfaces the current state and opens that
 * dialog. Desktop needs none of it.
 */
@Composable
fun BackgroundSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    var exempt by remember {
        mutableStateOf(com.typebit.platform.Platform.batteryOptimizationExempt())
    }
    var serviceUp by remember {
        mutableStateOf(com.typebit.platform.Platform.backgroundModeEnabled())
    }
    val scope = rememberCoroutineScope()

    if (!com.typebit.platform.Platform.isDesktop) {
        SectionCard("后台下载（Android）") {
            SettingSwitch(
                    "锁屏后继续下载 / 做种",
                    "前台服务 + 唤醒锁保证进程存活；关闭后仅前台运行",
                    settings.behavior.backgroundDownloads,
                    { on ->
                        // The master switch is a REAL setting: it gates the
                        // foreground service in AppStore.refreshStats.
                        onChange(settings.copy(behavior = settings.behavior.copy(backgroundDownloads = on)))
                        scope.launch {
                            kotlinx.coroutines.delay(300)
                            serviceUp = com.typebit.platform.Platform.backgroundModeEnabled()
                        }
                    },
            )
            SettingNote(
                    "下载/做种期间，通知栏会常驻一条「正在传输」状态；锁屏后仍继续传输。",
            )
            if (!exempt) {
                Button(
                        onClick = {
                            com.typebit.platform.Platform.openBatteryOptimizationSettings()
                            scope.launch {
                                kotlinx.coroutines.delay(2_000)
                                exempt = com.typebit.platform.Platform.batteryOptimizationExempt()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                ) { Text("忽略电池优化") }
                SettingNote(
                        "请在弹出的系统对话框中选择「允许」；若未弹出，将打开应用详情页，请在「电池」中允许后台运行。",
                )
            } else {
                SettingNote("已忽略电池优化，锁屏后台下载已就绪。")
            }
        }
    } else {
        SectionCard("后台下载") {
            SettingNote(
                    "桌面版在窗口打开期间持续传输；关闭窗口即停止。无需额外配置。",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// WebUI
// ---------------------------------------------------------------------------

@Composable
fun WebUiSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val s = settings.webUi
    val update: (WebUiSettings) -> Unit = { ns -> onChange(settings.copy(webUi = ns)) }

    SectionCard("WebUI") {
        Text(
                "内置 WebUI 服务在 0.1.0 中尚未提供（路线图项目）；以下设置会持久化，供未来版本使用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingSwitch("启用 WebUI", "", s.enabled, { update(s.copy(enabled = it)) })
        SettingNumberField(
                "端口",
                s.port.toString(),
                { update(s.copy(port = it.toIntOrNull() ?: s.port)) }
        )
        SettingTextField("用户名", s.username, { update(s.copy(username = it)) })
        SettingTextField(
                "密码",
                "",
                { if (it.isNotBlank()) update(s.copy(passwordHash = it.hashCode().toString())) }
        )
        SettingSwitch(
                "主机头校验",
                "",
                s.hostHeaderValidation,
                { update(s.copy(hostHeaderValidation = it)) }
        )
        SettingSwitch("启用 HTTPS", "", s.httpsEnabled, { update(s.copy(httpsEnabled = it)) })
        SettingNumberField(
                "会话超时 (分钟)",
                s.sessionTimeoutMinutes.toString(),
                {
                    update(
                            s.copy(
                                    sessionTimeoutMinutes = it.toLongOrNull()
                                                    ?: s.sessionTimeoutMinutes
                            )
                    )
                }
        )
        SettingSwitch("CSRF 保护", "", s.csrfProtection, { update(s.copy(csrfProtection = it)) })
        SettingSwitch(
                "反点击劫持",
                "",
                s.clickjackingProtection,
                { update(s.copy(clickjackingProtection = it)) }
        )
    }
}

// ---------------------------------------------------------------------------
// 高级
// ---------------------------------------------------------------------------

@Composable
fun AdvancedSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val s = settings.advanced
    val update: (AdvancedSettings) -> Unit = { ns -> onChange(settings.copy(advanced = ns)) }

    SectionCard("磁盘与缓存") {
        SettingNumberField(
                "磁盘缓存 (MiB)",
                (s.diskCacheBytes / 1024 / 1024).toString(),
                { update(s.copy(diskCacheBytes = (it.toLongOrNull() ?: 256L) * 1024 * 1024)) },
                suffix = "MiB"
        )
        SettingNumberField(
                "保存恢复数据间隔 (秒)",
                s.saveResumeDataIntervalSec.toString(),
                {
                    update(
                            s.copy(
                                    saveResumeDataIntervalSec = it.toIntOrNull()
                                                    ?: s.saveResumeDataIntervalSec
                            )
                    )
                }
        )
        SettingSwitch("使用操作系统缓存", "", s.osCache, { update(s.copy(osCache = it)) })
    }

    SectionCard("网络") {
        SettingNumberField(
                "网络缓冲区 (KiB)",
                s.networkBufferSizeKib.toString(),
                {
                    update(
                            s.copy(
                                    networkBufferSizeKib = it.toIntOrNull()
                                                    ?: s.networkBufferSizeKib
                            )
                    )
                }
        )
        SettingNumberField(
                "Socket 积压数",
                s.socketBacklogSize.toString(),
                { update(s.copy(socketBacklogSize = it.toIntOrNull() ?: s.socketBacklogSize)) }
        )
        SettingNumberField(
                "发送缓冲水位 (KiB)",
                s.sendBufferWatermarkKib.toString(),
                {
                    update(
                            s.copy(
                                    sendBufferWatermarkKib = it.toIntOrNull()
                                                    ?: s.sendBufferWatermarkKib
                            )
                    )
                }
        )
        SettingDropdown(
                label = "uTP 混合模式",
                options = UtpMixedMode.entries,
                selected = s.utpMixedMode,
                onSelect = { update(s.copy(utpMixedMode = it)) },
                labelOf = {
                    when (it) {
                        UtpMixedMode.PREFER_TCP -> "优先 TCP"
                        UtpMixedMode.PEER_PROPORTIONAL -> "按 Peer 比例"
                        UtpMixedMode.TCP -> "仅 TCP"
                    }
                },
        )
        SettingSwitch(
                "地址变化时重新通告",
                "",
                s.reannounceWhenAddressChanged,
                { update(s.copy(reannounceWhenAddressChanged = it)) }
        )
        SettingSwitch(
                "解析 Peer 国家",
                "",
                s.resolvePeerCountries,
                { update(s.copy(resolvePeerCountries = it)) }
        )
        SettingSwitch(
                "解析 Peer 主机名",
                "",
                s.resolvePeerHostNames,
                { update(s.copy(resolvePeerHostNames = it)) }
        )
        SettingNumberField(
                "Peer 轮换间隔 (秒)",
                s.peerTurnOverIntervalSec.toString(),
                {
                    update(
                            s.copy(
                                    peerTurnOverIntervalSec = it.toIntOrNull()
                                                    ?: s.peerTurnOverIntervalSec
                            )
                    )
                }
        )
        SettingSwitch("Suggestion 模式", "", s.suggestMode, { update(s.copy(suggestMode = it)) })
    }

    SectionCard("Tracker") {
        SettingNumberField(
                "最大并发 HTTP 通告",
                s.maxConcurrentHttpAnnounces.toString(),
                {
                    update(
                            s.copy(
                                    maxConcurrentHttpAnnounces = it.toIntOrNull()
                                                    ?: s.maxConcurrentHttpAnnounces
                            )
                    )
                }
        )
        SettingNumberField(
                "停止 Tracker 超时 (秒)",
                s.stopTrackerTimeoutSec.toString(),
                {
                    update(
                            s.copy(
                                    stopTrackerTimeoutSec = it.toIntOrNull()
                                                    ?: s.stopTrackerTimeoutSec
                            )
                    )
                }
        )
        SettingNumberField(
                "Tracker 失败次数上限",
                s.trackerFailsLimit.toString(),
                { update(s.copy(trackerFailsLimit = it.toIntOrNull() ?: s.trackerFailsLimit)) }
        )
        SettingNumberField(
                "Tracker 重试间隔 (秒)",
                s.trackerRetryIntervalSec.toString(),
                {
                    update(
                            s.copy(
                                    trackerRetryIntervalSec = it.toIntOrNull()
                                                    ?: s.trackerRetryIntervalSec
                            )
                    )
                }
        )
        SettingNumberField(
                "Tracker 重试次数",
                s.trackerRetryNum.toString(),
                { update(s.copy(trackerRetryNum = it.toIntOrNull() ?: s.trackerRetryNum)) }
        )
    }

    SectionCard("解析安全") {
        SettingNumberField(
                "Bencode 深度限制",
                s.bdecodeDepthLimit.toString(),
                { update(s.copy(bdecodeDepthLimit = it.toIntOrNull() ?: s.bdecodeDepthLimit)) }
        )
        SettingNumberField(
                "Bencode Token 上限",
                s.bdecodeTokenLimit.toString(),
                { update(s.copy(bdecodeTokenLimit = it.toLongOrNull() ?: s.bdecodeTokenLimit)) }
        )
    }
}

// ---------------------------------------------------------------------------
// RSS
// ---------------------------------------------------------------------------

@Composable
fun RssSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val s = settings.rss
    val update: (RssSettings) -> Unit = { ns -> onChange(settings.copy(rss = ns)) }

    SectionCard("RSS 阅读器") {
        SettingNumberField(
                "刷新间隔 (分钟)",
                s.refreshIntervalMin.toString(),
                { update(s.copy(refreshIntervalMin = it.toIntOrNull() ?: s.refreshIntervalMin)) }
        )
        SettingNumberField(
                "每源最大文章数",
                s.maxArticlesPerFeed.toString(),
                { update(s.copy(maxArticlesPerFeed = it.toIntOrNull() ?: s.maxArticlesPerFeed)) }
        )
        SettingSwitch(
                "智能剧集过滤",
                "自动识别 S01E01 等剧集命名",
                s.smartEpisodeFilter,
                { update(s.copy(smartEpisodeFilter = it)) }
        )
        SettingSwitch(
                "自动下载报告器",
                "监听 RSS 自动下载事件",
                s.autoDownloadReporterEnabled,
                { update(s.copy(autoDownloadReporterEnabled = it)) }
        )
        if (s.autoDownloadReporterEnabled) {
            SettingNumberField(
                    "报告器端口",
                    s.downloadReporterPort.toString(),
                    {
                        update(
                                s.copy(
                                        downloadReporterPort = it.toIntOrNull()
                                                        ?: s.downloadReporterPort
                                )
                        )
                    }
            )
            SettingTextField(
                    "报告器 Token",
                    s.downloadReporterToken,
                    { update(s.copy(downloadReporterToken = it)) }
            )
        }
    }
}
