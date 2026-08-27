package com.typebit.data

import kotlinx.serialization.Serializable

/**
 * The full application settings model.
 *
 * Organized exactly like qBittorrent's options dialog so users coming from
 * qBittorrent/BitComet feel at home. Every category is a separate
 * `@Serializable` value class with qBittorrent-compatible defaults.
 *
 * Honesty notes (README has the details):
 * - Fields the `typebit 0.1.0` engine can actually drive are wired through
 *   to the native bridge (ports, cache, DHT, peer limits, scheduler, choke,
 *   global speed limits, tracker lists).
 * - Fields that only make sense for libtorrent (encryption mode, UPnP
 *   lease, uTP, …) are stored + shown in the UI and marked as
 *   "engine-agnostic" — see `docs/settings.md`.
 */

@Serializable
data class AppSettings(
    val behavior: BehaviorSettings = BehaviorSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val downloads: DownloadSettings = DownloadSettings(),
    val connection: ConnectionSettings = ConnectionSettings(),
    val speed: SpeedSettings = SpeedSettings(),
    val bitTorrent: BitTorrentSettings = BitTorrentSettings(),
    val webUi: WebUiSettings = WebUiSettings(),
    val advanced: AdvancedSettings = AdvancedSettings(),
    val rss: RssSettings = RssSettings(),
)

// ---------------------------------------------------------------------------
// 外观 (Material You / MD3-Expressive)
// ---------------------------------------------------------------------------

/** How the app decides light vs dark, mirroring Android's theme preference. */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/**
 * Which bundled Google Font stack the UI uses.
 *
 * Latin/CJK mixed stacks pair a Latin font with Noto Sans SC so Chinese
 * text always renders from a bundled CJK font (never a missing-glyph box).
 * `SYSTEM` uses the platform default (smallest download, no bundle).
 */
@Serializable
enum class FontChoice {
    /** Inter (Latin) + Noto Sans SC (CJK) — the app default. */
    DEFAULT,
    /** Roboto (Latin) + Noto Sans SC (CJK). */
    ROBOTO,
    /** Open Sans (Latin) + Noto Sans SC (CJK). */
    OPEN_SANS,
    /** Noto Sans SC only (pure CJK / global). */
    NOTO_SANS,
    /** Platform system default font. */
    SYSTEM,
}

@Serializable
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Bundled Google Font stack (see [FontChoice]). */
    val fontChoice: FontChoice = FontChoice.DEFAULT,
    /** Wallpaper is shown blurred + dimmed behind the whole UI. */
    val wallpaperEnabled: Boolean = false,
    /**
     * Absolute path of the wallpaper. Android always stores a private copy
     * (SAF content URIs are not stable across sessions) — the picker writes
     * the copy into the app-data dir and returns that path.
     */
    val wallpaperPath: String = "",
    /** Gaussian blur radius in px applied to the wallpaper. */
    val blurRadiusPx: Float = 24f,
    /** Black (dark) / white (light) readability scrim, 0..0.85. */
    val dimAlpha: Float = 0.45f,
    /**
     * `false` = Crop (fill the window, may cut edges), `true` = Fit (show
     * the whole image, letterboxed). Mirrors desktop "fill / fit" choices.
     */
    val wallpaperFit: Boolean = false,
    /** Vertical pan of the wallpaper in -1..1 (only effective in Crop). */
    val wallpaperOffsetY: Float = 0f,
    /**
     * Optional explicit seed color (ARGB). When set it wins over the color
     * extracted from the wallpaper — a manual Monet override.
     */
    val seedOverride: Int? = null,
)

// ---------------------------------------------------------------------------
// 行为
// ---------------------------------------------------------------------------

@Serializable
data class BehaviorSettings(
    val language: String = "system",
    val confirmOnExit: Boolean = true,
    val confirmOnDelete: Boolean = true,
    val confirmOnRemoveTag: Boolean = true,
    val startMinimized: Boolean = false,
    val minimizeToTray: Boolean = false,
    val closeToTray: Boolean = false,
    val showNotifications: Boolean = true,
    val notifyOnDownloadAdded: Boolean = true,
    val notifyOnDownloadFinished: Boolean = true,
    val notifyOnNewVersion: Boolean = false,
    /**
     * Whether the Android foreground service (dataSync + wake lock) may run
     * while a torrent is active — the "锁屏后继续下载" master switch. When
     * off, transfers only run while the app is foregrounded.
     */
    val backgroundDownloads: Boolean = true,
    /** UI refresh cadence in ms (also drives the engine poll loop). */
    val refreshIntervalMs: Int = 500,
)

// ---------------------------------------------------------------------------
// 下载
// ---------------------------------------------------------------------------

/** When a torrent should be stopped automatically. */
@Serializable
enum class StopCondition { NONE, METADATA, RATIO, TIME, BOTH }

@Serializable
data class DownloadSettings(
    val defaultSavePath: String = "",
    val useTempPath: Boolean = false,
    val tempPath: String = "",
    val preAllocateDisk: Boolean = false,
    val addTorrentsInPause: Boolean = false,
    val maxActiveDownloads: Int = 3,
    val maxActiveUploads: Int = 3,
    val maxActiveTorrents: Int = 5,
    /** Automatic Torrent Management (category → save path). */
    val autoTmmEnabled: Boolean = false,
    val categorySavePaths: Map<String, String> = emptyMap(),
    val ratioLimitEnabled: Boolean = false,
    val ratioLimit: Double = 2.0,
    val globalRatioLimit: Double = 2.0,
    val timeLimitEnabled: Boolean = false,
    val timeLimitMinutes: Long = 60,
    val globalTimeLimitMinutes: Long = 60,
    val stopCondition: StopCondition = StopCondition.NONE,
)

// ---------------------------------------------------------------------------
// 连接
// ---------------------------------------------------------------------------

@Serializable
enum class ProtocolMode { TCP_AND_UDP, TCP_ONLY, UDP_ONLY }

/**
 * Proxy kind. The Rust engine (`typebit`) currently implements **SOCKS5**
 * only; the `SOCKS4`/`HTTP` values are retained solely so old persisted
 * settings files still decode — they are treated as "not usable" and never
 * enable a proxy (see [EngineConfigJson]).
 */
@Serializable
enum class ProxyType { NONE, SOCKS4, SOCKS5, HTTP }

@Serializable
data class ConnectionSettings(
    val listenPort: Int = 6881,
    val useRandomPort: Boolean = false,
    val maxConnections: Int = 500,
    val maxConnectionsPerTorrent: Int = 100,
    /** Global upload slots (concurrent unchoked peers). */
    val maxUploads: Int = 8,
    val maxUploadsPerTorrent: Int = 4,
    val protocol: ProtocolMode = ProtocolMode.TCP_AND_UDP,
    val networkInterface: String = "",
    val proxyType: ProxyType = ProxyType.NONE,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyAuthEnabled: Boolean = false,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val ipFilterEnabled: Boolean = false,
    val ipFilterPath: String = "",
    val anonymizationEnabled: Boolean = false,
    val announceToAllTrackers: Boolean = true,
    val announceToAllTiers: Boolean = true,
    val peerTos: Int = 0,
)

// ---------------------------------------------------------------------------
// 速度
// ---------------------------------------------------------------------------

/** Which day set the alternative-limit schedule applies to. */
@Serializable
enum class ScheduleDays { EVERY_DAY, WEEKDAYS, WEEKEND, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

@Serializable
data class SpeedSettings(
    /** Global download limit in KiB/s (0 = unlimited). */
    val globalDownloadLimitKib: Long = 0,
    /** Global upload limit in KiB/s (0 = unlimited). */
    val globalUploadLimitKib: Long = 0,
    val altDownloadLimitKib: Long = 10240,
    val altUploadLimitKib: Long = 3072,
    /** Alternative limits are active when the schedule window is open. */
    val alternativeLimitsEnabled: Boolean = false,
    val scheduleEnabled: Boolean = false,
    val scheduleFromHour: Int = 8,
    val scheduleFromMinute: Int = 0,
    val scheduleToHour: Int = 20,
    val scheduleToMinute: Int = 0,
    val scheduleDays: ScheduleDays = ScheduleDays.EVERY_DAY,
    val slowTorrentRateKib: Int = 2,
    val slowTorrentInactiveSec: Int = 60,
)

// ---------------------------------------------------------------------------
// BitTorrent
// ---------------------------------------------------------------------------

@Serializable
enum class EncryptionMode { ALLOW, PREFER, REQUIRE }

/** How to place multi-file torrents on disk. */
@Serializable
enum class ContentLayout { ORIGINAL, SUBFOLDER, NO_SUBFOLDER }

@Serializable
data class BitTorrentSettings(
    val enableDht: Boolean = true,
    val enablePex: Boolean = true,
    val enableLsd: Boolean = true,
    val encryptionMode: EncryptionMode = EncryptionMode.PREFER,
    val enableUpnp: Boolean = true,
    val enableNatPmp: Boolean = true,
    val maxPeersPerTorrent: Int = 80,
    val requestPipeline: Int = 256,
    val endgamePieces: Int = 32,
    val smartScheduling: Boolean = true,
    val useDefaultTrackers: Boolean = true,
    val antiLeechEnabled: Boolean = true,
    /** Extra tracker announce URLs, one per line. */
    val extraTrackers: String = "",
    /** Disk write-back cache budget in bytes. */
    val cacheBytes: Long = 256L * 1024 * 1024,
    val seedingSlots: Int = 8,
    val leechingSlots: Int = 8,
    val optimisticIntervalMs: Long = 30_000,
    val snubTimeoutMs: Long = 60_000,
    val chokeIntervalMs: Long = 10_000,
    // utility scheduler weights (typebit-specific)
    val schedulerAlpha: Int = 8,
    val schedulerBeta: Int = 2,
    val schedulerGamma: Int = 1,
    val schedulerDelta: Int = 64,
    val schedulerEdgeBytes: Long = 4L * 1024 * 1024,
    val contentLayout: ContentLayout = ContentLayout.ORIGINAL,
    val anonymousMode: Boolean = false,
)

// ---------------------------------------------------------------------------
// WebUI
// ---------------------------------------------------------------------------

@Serializable
data class WebUiSettings(
    /** Roadmap: the built-in WebUI server is not shipped in 0.1.0. */
    val enabled: Boolean = true,
    val port: Int = 8080,
    val username: String = "admin",
    /** BCrypt-style password hash — never the plaintext. */
    val passwordHash: String = "",
    val hostHeaderValidation: Boolean = false,
    val httpsEnabled: Boolean = false,
    val maxAuthFailCount: Int = 5,
    val banDurationSec: Long = 3600,
    val sessionTimeoutMinutes: Long = 60,
    val csrfProtection: Boolean = true,
    val clickjackingProtection: Boolean = true,
    val localHostAuth: Boolean = true,
    val reverseProxyEnabled: Boolean = false,
)

// ---------------------------------------------------------------------------
// 高级
// ---------------------------------------------------------------------------

@Serializable
enum class UtpMixedMode { PREFER_TCP, PEER_PROPORTIONAL, TCP }

@Serializable
data class AdvancedSettings(
    val diskCacheBytes: Long = 256L * 1024 * 1024,
    val saveResumeDataIntervalSec: Int = 60,
    val osCache: Boolean = true,
    val networkBufferSizeKib: Int = 0,
    val socketBacklogSize: Int = 30,
    val sendBufferWatermarkKib: Int = 512,
    val upnpLeaseDurationSec: Long = 0,
    val utpMixedMode: UtpMixedMode = UtpMixedMode.PREFER_TCP,
    val idleSeedingLimitMinutes: Long = 0,
    val bdecodeDepthLimit: Int = 100,
    val bdecodeTokenLimit: Long = 50_000_000,
    val maxConcurrentHttpAnnounces: Int = 50,
    val stopTrackerTimeoutSec: Int = 5,
    val trackerFailsLimit: Int = 3,
    val trackerRetryIntervalSec: Int = 30,
    val trackerRetryNum: Int = 5,
    val reannounceWhenAddressChanged: Boolean = true,
    val resolvePeerCountries: Boolean = false,
    val resolvePeerHostNames: Boolean = false,
    val peerTurnOverIntervalSec: Int = 5,
    val suggestMode: Boolean = false,
    val maxOutstandingRequests: Int = 256,
)

// ---------------------------------------------------------------------------
// RSS
// ---------------------------------------------------------------------------

@Serializable
data class RssSettings(
    val refreshIntervalMin: Int = 30,
    val maxArticlesPerFeed: Int = 50,
    val autoDownloadReporterEnabled: Boolean = false,
    val smartEpisodeFilter: Boolean = true,
    val downloadReporterPort: Int = 0,
    val downloadReporterToken: String = "",
    val acceptedHosts: List<String> = emptyList(),
)
