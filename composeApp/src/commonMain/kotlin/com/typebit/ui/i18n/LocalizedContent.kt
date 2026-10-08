package com.typebit.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

const val SYSTEM_LANGUAGE = "system"
const val SIMPLIFIED_CHINESE = "zh-CN"
const val ENGLISH = "en-US"
const val TRADITIONAL_CHINESE = "zh-TW"

enum class TextKey {
    SETTINGS,
    BACK,
    CATEGORIES,
    CATEGORY_BEHAVIOR,
    CATEGORY_APPEARANCE,
    CATEGORY_DOWNLOADS,
    CATEGORY_CONNECTION,
    CATEGORY_SPEED,
    CATEGORY_BITTORRENT,
    CATEGORY_BACKGROUND,
    CATEGORY_WEBUI,
    CATEGORY_ADVANCED,
    CATEGORY_RSS,
    BEHAVIOR_INTERFACE,
    LANGUAGE,
    LANGUAGE_SYSTEM,
    LANGUAGE_SIMPLIFIED_CHINESE,
    LANGUAGE_ENGLISH,
    LANGUAGE_TRADITIONAL_CHINESE,
    START_MINIMIZED,
    START_MINIMIZED_DESCRIPTION,
    MINIMIZE_TO_TRAY,
    MINIMIZE_TO_TRAY_DESCRIPTION,
    CLOSE_TO_TRAY,
    CLOSE_TO_TRAY_DESCRIPTION,
    REFRESH_INTERVAL,
    BEHAVIOR_CONFIRMATION,
    CONFIRM_ON_EXIT,
    CONFIRM_ON_EXIT_DESCRIPTION,
    CONFIRM_ON_DELETE,
    CONFIRM_ON_DELETE_DESCRIPTION,
    CONFIRM_ON_REMOVE_TAG,
    BEHAVIOR_NOTIFICATIONS,
    ENABLE_NOTIFICATIONS,
    ENABLE_NOTIFICATIONS_DESCRIPTION,
    NOTIFY_DOWNLOAD_ADDED,
    NOTIFY_DOWNLOAD_FINISHED,
    NOTIFY_NEW_VERSION,
    NOTIFY_NEW_VERSION_DESCRIPTION,
    STATUS_DOWNLOADING,
    STATUS_SEEDING,
    STATUS_PAUSED,
    STATUS_FETCHING_METADATA,
    STATUS_STOPPED,
    STATUS_FAILED,
    ACTION_RENAME,
    ACTION_SHARE_MAGNET,
    ACTION_RESUME,
    ACTION_PAUSE,
    ACTION_DELETE,
    TORRENT_ACTION_SUMMARY,
    RENAME_TORRENT,
    NEW_TORRENT_NAME,
    CONFIRM,
    CANCEL,
    DELETE_TORRENT,
    DELETE_TORRENT_CONFIRMATION,
}

class Strings private constructor(private val values: Map<TextKey, String>) {
    operator fun get(key: TextKey): String =
        values[key] ?: error("Missing translation for $key")

    companion object {
        fun of(vararg entries: Pair<TextKey, String>): Strings {
            val values = entries.toMap()
            val missing = TextKey.entries.filterNot(values::containsKey)
            check(missing.isEmpty()) { "Missing translations: ${missing.joinToString()}" }
            return Strings(values)
        }
    }
}

val LocalStrings = staticCompositionLocalOf { simplifiedChinese }

/**
 * Applies the persisted app language immediately. `system` resolves once for
 * the current process; explicit selections are deterministic across platforms.
 */
@Composable
fun LocalizedContent(language: String, content: @Composable () -> Unit) {
    val resolved = if (language == SYSTEM_LANGUAGE) languageFromSystem() else language
    val strings =
        when (resolved) {
            ENGLISH -> english
            TRADITIONAL_CHINESE -> traditionalChinese
            else -> simplifiedChinese
        }
    CompositionLocalProvider(LocalStrings provides strings, content = content)
}

private fun languageFromSystem(): String =
    when (systemLanguageTag().lowercase()) {
        "zh-tw", "zh-hk", "zh-mo", "zh-hant" -> TRADITIONAL_CHINESE
        "en", "en-us", "en-gb" -> ENGLISH
        else -> SIMPLIFIED_CHINESE
    }

expect fun systemLanguageTag(): String

private val simplifiedChinese =
    Strings.of(
        TextKey.SETTINGS to "设置",
        TextKey.BACK to "返回",
        TextKey.CATEGORIES to "分类",
        TextKey.CATEGORY_BEHAVIOR to "行为",
        TextKey.CATEGORY_APPEARANCE to "外观",
        TextKey.CATEGORY_DOWNLOADS to "下载",
        TextKey.CATEGORY_CONNECTION to "连接",
        TextKey.CATEGORY_SPEED to "速度",
        TextKey.CATEGORY_BITTORRENT to "BitTorrent",
        TextKey.CATEGORY_BACKGROUND to "后台",
        TextKey.CATEGORY_WEBUI to "WebUI",
        TextKey.CATEGORY_ADVANCED to "高级",
        TextKey.CATEGORY_RSS to "RSS",
        TextKey.BEHAVIOR_INTERFACE to "界面",
        TextKey.LANGUAGE to "语言",
        TextKey.LANGUAGE_SYSTEM to "跟随系统",
        TextKey.LANGUAGE_SIMPLIFIED_CHINESE to "简体中文",
        TextKey.LANGUAGE_ENGLISH to "English",
        TextKey.LANGUAGE_TRADITIONAL_CHINESE to "繁體中文",
        TextKey.START_MINIMIZED to "启动时最小化",
        TextKey.START_MINIMIZED_DESCRIPTION to "应用启动后最小化到托盘/任务栏",
        TextKey.MINIMIZE_TO_TRAY to "最小化到托盘",
        TextKey.MINIMIZE_TO_TRAY_DESCRIPTION to "关闭窗口时最小化到系统托盘而非退出",
        TextKey.CLOSE_TO_TRAY to "关闭到托盘",
        TextKey.CLOSE_TO_TRAY_DESCRIPTION to "点击关闭时最小化到托盘",
        TextKey.REFRESH_INTERVAL to "刷新间隔 (ms)",
        TextKey.BEHAVIOR_CONFIRMATION to "确认",
        TextKey.CONFIRM_ON_EXIT to "退出时确认",
        TextKey.CONFIRM_ON_EXIT_DESCRIPTION to "退出应用前弹出确认对话框",
        TextKey.CONFIRM_ON_DELETE to "删除时确认",
        TextKey.CONFIRM_ON_DELETE_DESCRIPTION to "删除种子前弹出确认对话框",
        TextKey.CONFIRM_ON_REMOVE_TAG to "移除标签时确认",
        TextKey.BEHAVIOR_NOTIFICATIONS to "通知",
        TextKey.ENABLE_NOTIFICATIONS to "启用通知",
        TextKey.ENABLE_NOTIFICATIONS_DESCRIPTION to "下载事件系统通知",
        TextKey.NOTIFY_DOWNLOAD_ADDED to "添加种子时通知",
        TextKey.NOTIFY_DOWNLOAD_FINISHED to "下载完成时通知",
        TextKey.NOTIFY_NEW_VERSION to "新版本通知",
        TextKey.NOTIFY_NEW_VERSION_DESCRIPTION to "检测到新版本时提示",
        TextKey.STATUS_DOWNLOADING to "下载中",
        TextKey.STATUS_SEEDING to "做种",
        TextKey.STATUS_PAUSED to "已暂停",
        TextKey.STATUS_FETCHING_METADATA to "获取元数据",
        TextKey.STATUS_STOPPED to "已停止",
        TextKey.STATUS_FAILED to "出错",
        TextKey.ACTION_RENAME to "重命名",
        TextKey.ACTION_SHARE_MAGNET to "分享（磁力链接）",
        TextKey.ACTION_RESUME to "继续",
        TextKey.ACTION_PAUSE to "暂停",
        TextKey.ACTION_DELETE to "删除",
        TextKey.TORRENT_ACTION_SUMMARY to "种子：{seeds} / 下载者：{peers}",
        TextKey.RENAME_TORRENT to "重命名",
        TextKey.NEW_TORRENT_NAME to "新名称",
        TextKey.CONFIRM to "确定",
        TextKey.CANCEL to "取消",
        TextKey.DELETE_TORRENT to "删除种子",
        TextKey.DELETE_TORRENT_CONFIRMATION to "确定删除「{name}」吗？已下载的临时文件（.part）会被清理。",
    )

private val english =
    Strings.of(
        TextKey.SETTINGS to "Settings",
        TextKey.BACK to "Back",
        TextKey.CATEGORIES to "Categories",
        TextKey.CATEGORY_BEHAVIOR to "Behavior",
        TextKey.CATEGORY_APPEARANCE to "Appearance",
        TextKey.CATEGORY_DOWNLOADS to "Downloads",
        TextKey.CATEGORY_CONNECTION to "Connection",
        TextKey.CATEGORY_SPEED to "Speed",
        TextKey.CATEGORY_BITTORRENT to "BitTorrent",
        TextKey.CATEGORY_BACKGROUND to "Background",
        TextKey.CATEGORY_WEBUI to "WebUI",
        TextKey.CATEGORY_ADVANCED to "Advanced",
        TextKey.CATEGORY_RSS to "RSS",
        TextKey.BEHAVIOR_INTERFACE to "Interface",
        TextKey.LANGUAGE to "Language",
        TextKey.LANGUAGE_SYSTEM to "System default",
        TextKey.LANGUAGE_SIMPLIFIED_CHINESE to "Simplified Chinese",
        TextKey.LANGUAGE_ENGLISH to "English",
        TextKey.LANGUAGE_TRADITIONAL_CHINESE to "Traditional Chinese",
        TextKey.START_MINIMIZED to "Start minimized",
        TextKey.START_MINIMIZED_DESCRIPTION to "Minimize to the tray or taskbar when the application starts.",
        TextKey.MINIMIZE_TO_TRAY to "Minimize to tray",
        TextKey.MINIMIZE_TO_TRAY_DESCRIPTION to "Minimize to the system tray instead of exiting when the window is closed.",
        TextKey.CLOSE_TO_TRAY to "Close to tray",
        TextKey.CLOSE_TO_TRAY_DESCRIPTION to "Minimize to the system tray when Close is selected.",
        TextKey.REFRESH_INTERVAL to "Refresh interval (ms)",
        TextKey.BEHAVIOR_CONFIRMATION to "Confirmation",
        TextKey.CONFIRM_ON_EXIT to "Confirm before exiting",
        TextKey.CONFIRM_ON_EXIT_DESCRIPTION to "Ask for confirmation before the application exits.",
        TextKey.CONFIRM_ON_DELETE to "Confirm before deleting",
        TextKey.CONFIRM_ON_DELETE_DESCRIPTION to "Ask for confirmation before deleting a torrent.",
        TextKey.CONFIRM_ON_REMOVE_TAG to "Confirm before removing a tag",
        TextKey.BEHAVIOR_NOTIFICATIONS to "Notifications",
        TextKey.ENABLE_NOTIFICATIONS to "Enable notifications",
        TextKey.ENABLE_NOTIFICATIONS_DESCRIPTION to "Show system notifications for download events.",
        TextKey.NOTIFY_DOWNLOAD_ADDED to "Notify when a torrent is added",
        TextKey.NOTIFY_DOWNLOAD_FINISHED to "Notify when a download finishes",
        TextKey.NOTIFY_NEW_VERSION to "Notify about new versions",
        TextKey.NOTIFY_NEW_VERSION_DESCRIPTION to "Notify when an update is available.",
        TextKey.STATUS_DOWNLOADING to "Downloading",
        TextKey.STATUS_SEEDING to "Seeding",
        TextKey.STATUS_PAUSED to "Paused",
        TextKey.STATUS_FETCHING_METADATA to "Fetching metadata",
        TextKey.STATUS_STOPPED to "Stopped",
        TextKey.STATUS_FAILED to "Error",
        TextKey.ACTION_RENAME to "Rename",
        TextKey.ACTION_SHARE_MAGNET to "Share magnet link",
        TextKey.ACTION_RESUME to "Resume",
        TextKey.ACTION_PAUSE to "Pause",
        TextKey.ACTION_DELETE to "Delete",
        TextKey.TORRENT_ACTION_SUMMARY to "Seeds: {seeds} / peers: {peers}",
        TextKey.RENAME_TORRENT to "Rename torrent",
        TextKey.NEW_TORRENT_NAME to "New name",
        TextKey.CONFIRM to "Confirm",
        TextKey.CANCEL to "Cancel",
        TextKey.DELETE_TORRENT to "Delete torrent",
        TextKey.DELETE_TORRENT_CONFIRMATION to "Delete “{name}”? Downloaded temporary (.part) files will also be removed.",
    )

private val traditionalChinese =
    Strings.of(
        TextKey.SETTINGS to "設定",
        TextKey.BACK to "返回",
        TextKey.CATEGORIES to "分類",
        TextKey.CATEGORY_BEHAVIOR to "行為",
        TextKey.CATEGORY_APPEARANCE to "外觀",
        TextKey.CATEGORY_DOWNLOADS to "下載",
        TextKey.CATEGORY_CONNECTION to "連線",
        TextKey.CATEGORY_SPEED to "速度",
        TextKey.CATEGORY_BITTORRENT to "BitTorrent",
        TextKey.CATEGORY_BACKGROUND to "背景",
        TextKey.CATEGORY_WEBUI to "WebUI",
        TextKey.CATEGORY_ADVANCED to "進階",
        TextKey.CATEGORY_RSS to "RSS",
        TextKey.BEHAVIOR_INTERFACE to "介面",
        TextKey.LANGUAGE to "語言",
        TextKey.LANGUAGE_SYSTEM to "跟隨系統",
        TextKey.LANGUAGE_SIMPLIFIED_CHINESE to "簡體中文",
        TextKey.LANGUAGE_ENGLISH to "English",
        TextKey.LANGUAGE_TRADITIONAL_CHINESE to "繁體中文",
        TextKey.START_MINIMIZED to "啟動時最小化",
        TextKey.START_MINIMIZED_DESCRIPTION to "應用程式啟動後最小化至系統匣或工作列。",
        TextKey.MINIMIZE_TO_TRAY to "最小化至系統匣",
        TextKey.MINIMIZE_TO_TRAY_DESCRIPTION to "關閉視窗時最小化至系統匣，而不是結束應用程式。",
        TextKey.CLOSE_TO_TRAY to "關閉至系統匣",
        TextKey.CLOSE_TO_TRAY_DESCRIPTION to "選擇關閉時最小化至系統匣。",
        TextKey.REFRESH_INTERVAL to "重新整理間隔 (ms)",
        TextKey.BEHAVIOR_CONFIRMATION to "確認",
        TextKey.CONFIRM_ON_EXIT to "結束前確認",
        TextKey.CONFIRM_ON_EXIT_DESCRIPTION to "結束應用程式前顯示確認對話框。",
        TextKey.CONFIRM_ON_DELETE to "刪除前確認",
        TextKey.CONFIRM_ON_DELETE_DESCRIPTION to "刪除種子前顯示確認對話框。",
        TextKey.CONFIRM_ON_REMOVE_TAG to "移除標籤前確認",
        TextKey.BEHAVIOR_NOTIFICATIONS to "通知",
        TextKey.ENABLE_NOTIFICATIONS to "啟用通知",
        TextKey.ENABLE_NOTIFICATIONS_DESCRIPTION to "為下載事件顯示系統通知。",
        TextKey.NOTIFY_DOWNLOAD_ADDED to "新增種子時通知",
        TextKey.NOTIFY_DOWNLOAD_FINISHED to "下載完成時通知",
        TextKey.NOTIFY_NEW_VERSION to "新版本通知",
        TextKey.NOTIFY_NEW_VERSION_DESCRIPTION to "有可用更新時通知。",
        TextKey.STATUS_DOWNLOADING to "下載中",
        TextKey.STATUS_SEEDING to "做種中",
        TextKey.STATUS_PAUSED to "已暫停",
        TextKey.STATUS_FETCHING_METADATA to "取得中繼資料",
        TextKey.STATUS_STOPPED to "已停止",
        TextKey.STATUS_FAILED to "錯誤",
        TextKey.ACTION_RENAME to "重新命名",
        TextKey.ACTION_SHARE_MAGNET to "分享磁力連結",
        TextKey.ACTION_RESUME to "繼續",
        TextKey.ACTION_PAUSE to "暫停",
        TextKey.ACTION_DELETE to "刪除",
        TextKey.TORRENT_ACTION_SUMMARY to "種子：{seeds} / 下載者：{peers}",
        TextKey.RENAME_TORRENT to "重新命名種子",
        TextKey.NEW_TORRENT_NAME to "新名稱",
        TextKey.CONFIRM to "確認",
        TextKey.CANCEL to "取消",
        TextKey.DELETE_TORRENT to "刪除種子",
        TextKey.DELETE_TORRENT_CONFIRMATION to "確定要刪除「{name}」嗎？已下載的暫存檔（.part）也會被清除。",
    )
