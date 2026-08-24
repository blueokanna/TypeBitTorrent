package com.typebit.ui.screens.settings

import androidx.compose.runtime.Composable
import com.typebit.data.AppSettings
import com.typebit.data.BehaviorSettings
import com.typebit.data.ConnectionSettings
import com.typebit.data.DownloadSettings
import com.typebit.data.ProtocolMode
import com.typebit.data.ProxyType
import com.typebit.data.SpeedSettings
import com.typebit.data.StopCondition

// ---------------------------------------------------------------------------
// 行为
// ---------------------------------------------------------------------------

@Composable
fun BehaviorSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val s = settings.behavior
    val update: (BehaviorSettings) -> Unit = { ns -> onChange(settings.copy(behavior = ns)) }

    SectionCard("界面") {
        SettingDropdown(
            label = "语言",
            options = listOf("system", "zh-CN", "en-US"),
            selected = s.language,
            onSelect = { update(s.copy(language = it)) },
            labelOf = { when (it) {
                "system" -> "跟随系统"
                "zh-CN" -> "简体中文"
                else -> "English"
            } },
        )
        SettingSwitch(
            "启动时最小化", "应用启动后最小化到托盘/任务栏",
            s.startMinimized, { update(s.copy(startMinimized = it)) },
        )
        SettingSwitch(
            "最小化到托盘", "关闭窗口时最小化到系统托盘而非退出",
            s.minimizeToTray, { update(s.copy(minimizeToTray = it)) },
        )
        SettingSwitch(
            "关闭到托盘", "点击关闭时最小化到托盘",
            s.closeToTray, { update(s.copy(closeToTray = it)) },
        )
        SettingNumberField(
            "刷新间隔 (ms)", s.refreshIntervalMs.toString(),
            { update(s.copy(refreshIntervalMs = it.toIntOrNull() ?: s.refreshIntervalMs)) },
        )
    }

    SectionCard("确认") {
        SettingSwitch("退出时确认", "退出应用前弹出确认对话框", s.confirmOnExit, { update(s.copy(confirmOnExit = it)) })
        SettingSwitch("删除时确认", "删除种子前弹出确认对话框", s.confirmOnDelete, { update(s.copy(confirmOnDelete = it)) })
        SettingSwitch("移除标签时确认", "", s.confirmOnRemoveTag, { update(s.copy(confirmOnRemoveTag = it)) })
    }

    SectionCard("通知") {
        SettingSwitch("启用通知", "下载事件系统通知", s.showNotifications, { update(s.copy(showNotifications = it)) })
        SettingSwitch("添加种子时通知", "", s.notifyOnDownloadAdded, { update(s.copy(notifyOnDownloadAdded = it)) })
        SettingSwitch("下载完成时通知", "", s.notifyOnDownloadFinished, { update(s.copy(notifyOnDownloadFinished = it)) })
        SettingSwitch("新版本通知", "检测到新版本时提示", s.notifyOnNewVersion, { update(s.copy(notifyOnNewVersion = it)) })
    }
}

// ---------------------------------------------------------------------------
// 下载
// ---------------------------------------------------------------------------

@Composable
fun DownloadsSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val s = settings.downloads
    val update: (DownloadSettings) -> Unit = { ns -> onChange(settings.copy(downloads = ns)) }

    SectionCard("保存") {
        SettingTextField(
            "默认保存目录", s.defaultSavePath, { update(s.copy(defaultSavePath = it)) },
            placeholder = "留空则使用系统下载目录",
        )
        SettingSwitch(
            "启用临时目录", "下载期间使用临时目录，完成后移动到默认目录",
            s.useTempPath, { update(s.copy(useTempPath = it)) },
        )
        if (s.useTempPath) {
            SettingTextField("临时目录", s.tempPath, { update(s.copy(tempPath = it)) })
        }
        SettingSwitch("预分配磁盘空间", "开始下载前预分配完整文件大小", s.preAllocateDisk, { update(s.copy(preAllocateDisk = it)) })
        SettingSwitch("以暂停状态添加种子", "", s.addTorrentsInPause, { update(s.copy(addTorrentsInPause = it)) })
    }

    SectionCard("活动限制") {
        SettingNumberField("最大活动下载数", s.maxActiveDownloads.toString(), { update(s.copy(maxActiveDownloads = it.toIntOrNull() ?: s.maxActiveDownloads)) })
        SettingNumberField("最大活动上传数", s.maxActiveUploads.toString(), { update(s.copy(maxActiveUploads = it.toIntOrNull() ?: s.maxActiveUploads)) })
        SettingNumberField("最大活动种子数", s.maxActiveTorrents.toString(), { update(s.copy(maxActiveTorrents = it.toIntOrNull() ?: s.maxActiveTorrents)) })
    }

    SectionCard("自动管理") {
        SettingSwitch(
            "自动种子管理 (TMM)",
            "按分类的保存路径自动整理下载",
            s.autoTmmEnabled, { update(s.copy(autoTmmEnabled = it)) },
        )
    }

    SectionCard("做种限制") {
        SettingSwitch("启用分享率限制", "达到分享率后自动停止做种", s.ratioLimitEnabled, { update(s.copy(ratioLimitEnabled = it)) })
        if (s.ratioLimitEnabled) {
            SettingNumberField("全局分享率", s.globalRatioLimit.toString(), { update(s.copy(globalRatioLimit = it.toDoubleOrNull() ?: s.globalRatioLimit)) })
        }
        SettingSwitch("启用做种时间限制", "达到时间后自动停止做种", s.timeLimitEnabled, { update(s.copy(timeLimitEnabled = it)) })
        if (s.timeLimitEnabled) {
            SettingNumberField("做种时间 (分钟)", s.globalTimeLimitMinutes.toString(), { update(s.copy(globalTimeLimitMinutes = it.toLongOrNull() ?: s.globalTimeLimitMinutes)) })
        }
        SettingDropdown(
            label = "停止条件",
            options = StopCondition.entries,
            selected = s.stopCondition,
            onSelect = { update(s.copy(stopCondition = it)) },
            labelOf = { when (it) {
                StopCondition.NONE -> "无"
                StopCondition.METADATA -> "获取到元数据"
                StopCondition.RATIO -> "达到分享率"
                StopCondition.TIME -> "达到做种时间"
                StopCondition.BOTH -> "分享率或做种时间"
            } },
        )
    }
}

// ---------------------------------------------------------------------------
// 连接
// ---------------------------------------------------------------------------

@Composable
fun ConnectionSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val s = settings.connection
    val update: (ConnectionSettings) -> Unit = { ns -> onChange(settings.copy(connection = ns)) }

    SectionCard("监听端口") {
        SettingNumberField("监听端口", s.listenPort.toString(), { update(s.copy(listenPort = it.toIntOrNull() ?: s.listenPort)) })
        SettingSwitch("使用随机端口", "启动时随机选择空闲端口", s.useRandomPort, { update(s.copy(useRandomPort = it)) })
    }

    SectionCard("连接限制") {
        SettingNumberField("全局最大连接数", s.maxConnections.toString(), { update(s.copy(maxConnections = it.toIntOrNull() ?: s.maxConnections)) })
        SettingNumberField("每种子最大连接数", s.maxConnectionsPerTorrent.toString(), { update(s.copy(maxConnectionsPerTorrent = it.toIntOrNull() ?: s.maxConnectionsPerTorrent)) })
        SettingNumberField("全局上传槽位", s.maxUploads.toString(), { update(s.copy(maxUploads = it.toIntOrNull() ?: s.maxUploads)) })
        SettingNumberField("每种子上传槽位", s.maxUploadsPerTorrent.toString(), { update(s.copy(maxUploadsPerTorrent = it.toIntOrNull() ?: s.maxUploadsPerTorrent)) })
        SettingDropdown(
            label = "协议",
            options = ProtocolMode.entries,
            selected = s.protocol,
            onSelect = { update(s.copy(protocol = it)) },
            labelOf = { when (it) {
                ProtocolMode.TCP_AND_UDP -> "TCP 和 UDP"
                ProtocolMode.TCP_ONLY -> "仅 TCP"
                ProtocolMode.UDP_ONLY -> "仅 UDP"
            } },
        )
        SettingTextField("网络接口", s.networkInterface, { update(s.copy(networkInterface = it)) }, placeholder = "留空 = 自动")
    }

    SectionCard("代理") {
        SettingDropdown(
            label = "代理类型",
            options = ProxyType.entries,
            selected = s.proxyType,
            onSelect = { update(s.copy(proxyType = it)) },
            labelOf = { when (it) {
                ProxyType.NONE -> "无"
                ProxyType.SOCKS4 -> "SOCKS4"
                ProxyType.SOCKS5 -> "SOCKS5"
                ProxyType.HTTP -> "HTTP"
            } },
        )
        if (s.proxyType != ProxyType.NONE) {
            SettingTextField("代理地址", s.proxyHost, { update(s.copy(proxyHost = it)) })
            SettingNumberField("代理端口", s.proxyPort.toString(), { update(s.copy(proxyPort = it.toIntOrNull() ?: s.proxyPort)) })
            SettingSwitch("代理需要认证", "", s.proxyAuthEnabled, { update(s.copy(proxyAuthEnabled = it)) })
            if (s.proxyAuthEnabled) {
                SettingTextField("用户名", s.proxyUsername, { update(s.copy(proxyUsername = it)) })
                SettingTextField("密码", s.proxyPassword, { update(s.copy(proxyPassword = it)) })
            }
            SettingSwitch("用于 Tracker", "", s.proxyUseForTracker, { update(s.copy(proxyUseForTracker = it)) })
            SettingSwitch("用于 Peers", "", s.proxyUseForPeers, { update(s.copy(proxyUseForPeers = it)) })
            SettingSwitch("用于 DHT", "", s.proxyUseForDht, { update(s.copy(proxyUseForDht = it)) })
        }
    }

    SectionCard("高级") {
        SettingSwitch("启用 IP 过滤器", "", s.ipFilterEnabled, { update(s.copy(ipFilterEnabled = it)) })
        if (s.ipFilterEnabled) {
            SettingTextField("过滤器文件路径", s.ipFilterPath, { update(s.copy(ipFilterPath = it)) })
        }
        SettingSwitch("匿名模式", "对 Tracker 隐藏客户端信息（实验性）", s.anonymizationEnabled, { update(s.copy(anonymizationEnabled = it)) })
        SettingSwitch("向所有 Tracker 通告", "", s.announceToAllTrackers, { update(s.copy(announceToAllTrackers = it)) })
        SettingSwitch("向所有层级通告", "", s.announceToAllTiers, { update(s.copy(announceToAllTiers = it)) })
        SettingNumberField("Peer TOS", s.peerTos.toString(), { update(s.copy(peerTos = it.toIntOrNull() ?: s.peerTos)) })
    }
}

// ---------------------------------------------------------------------------
// 速度
// ---------------------------------------------------------------------------

@Composable
fun SpeedSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val s = settings.speed
    val update: (SpeedSettings) -> Unit = { ns -> onChange(settings.copy(speed = ns)) }

    SectionCard("全局速度限制") {
        SettingNumberField("全局下载限制 (KiB/s)", s.globalDownloadLimitKib.toString(), { update(s.copy(globalDownloadLimitKib = it.toLongOrNull() ?: s.globalDownloadLimitKib)) }, suffix = "KiB/s")
        SettingNumberField("全局上传限制 (KiB/s)", s.globalUploadLimitKib.toString(), { update(s.copy(globalUploadLimitKib = it.toLongOrNull() ?: s.globalUploadLimitKib)) }, suffix = "KiB/s")
    }

    SectionCard("备用速度限制") {
        SettingNumberField("备用下载限制 (KiB/s)", s.altDownloadLimitKib.toString(), { update(s.copy(altDownloadLimitKib = it.toLongOrNull() ?: s.altDownloadLimitKib)) }, suffix = "KiB/s")
        SettingNumberField("备用上传限制 (KiB/s)", s.altUploadLimitKib.toString(), { update(s.copy(altUploadLimitKib = it.toLongOrNull() ?: s.altUploadLimitKib)) }, suffix = "KiB/s")
        SettingSwitch(
            "启用备用限制调度",
            "在设定的时间段内使用备用速度限制",
            s.scheduleEnabled && s.alternativeLimitsEnabled,
            { on -> update(s.copy(scheduleEnabled = on, alternativeLimitsEnabled = on)) },
        )
        if (s.scheduleEnabled) {
            SettingNumberField("开始时间 (时)", s.scheduleFromHour.toString(), { update(s.copy(scheduleFromHour = it.toIntOrNull() ?: s.scheduleFromHour)) })
            SettingNumberField("结束时间 (时)", s.scheduleToHour.toString(), { update(s.copy(scheduleToHour = it.toIntOrNull() ?: s.scheduleToHour)) })
            SettingDropdown(
                label = "应用日期",
                options = com.typebit.data.ScheduleDays.entries,
                selected = s.scheduleDays,
                onSelect = { update(s.copy(scheduleDays = it)) },
                labelOf = { when (it) {
                    com.typebit.data.ScheduleDays.EVERY_DAY -> "每天"
                    com.typebit.data.ScheduleDays.WEEKDAYS -> "工作日"
                    com.typebit.data.ScheduleDays.WEEKEND -> "周末"
                    else -> it.name
                } },
            )
        }
    }

    SectionCard("慢速种子检测") {
        SettingNumberField("慢速阈值 (KiB/s)", s.slowTorrentRateKib.toString(), { update(s.copy(slowTorrentRateKib = it.toIntOrNull() ?: s.slowTorrentRateKib)) })
        SettingNumberField("判定时间 (秒)", s.slowTorrentInactiveSec.toString(), { update(s.copy(slowTorrentInactiveSec = it.toIntOrNull() ?: s.slowTorrentInactiveSec)) })
    }
}
