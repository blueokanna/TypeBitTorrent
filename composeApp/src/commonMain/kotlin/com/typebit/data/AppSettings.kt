package com.typebit.data

import kotlinx.serialization.Serializable

/**
 * Fields the engine can drive are wired through the native bridge; a few (encryption mode, uTP,
 * UPnP lease, …) are stored and shown only — see `docs/settings.md`.
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

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** Latin stacks are paired with Noto Sans SC, so CJK text never renders missing-glyph boxes. */
@Serializable
enum class FontChoice {
    /** Inter + Noto Sans SC. */
    DEFAULT,
    ROBOTO,
    OPEN_SANS,
    /** Noto Sans SC only. */
    NOTO_SANS,
    SYSTEM,
}

@Serializable
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontChoice: FontChoice = FontChoice.DEFAULT,
    val wallpaperEnabled: Boolean = false,
    /** Android stores a private copy: SAF content URIs are not stable across sessions. */
    val wallpaperPath: String = "",
    /** Gaussian blur radius in px. */
    val blurRadiusPx: Float = 24f,
    /** Black (dark) / white (light) readability scrim, 0..0.85. */
    val dimAlpha: Float = 0.45f,
    /** false = Crop (fills the window), true = Fit (letterboxed whole image). */
    val wallpaperFit: Boolean = false,
    /** Vertical pan, -1..1 (Crop mode only). */
    val wallpaperOffsetY: Float = 0f,
    /** Explicit ARGB seed colour; wins over the colour extracted from the wallpaper. */
    val seedOverride: Int? = null,
)

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
    /** Android foreground service (dataSync + wake lock) may run while a torrent is active. */
    val backgroundDownloads: Boolean = true,
    /** UI refresh cadence in ms; also drives the engine poll loop. */
    val refreshIntervalMs: Int = 500,
)

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
    /** Category decides the save path (Automatic Torrent Management). */
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

@Serializable
enum class ProtocolMode { TCP_AND_UDP, TCP_ONLY, UDP_ONLY }

/**
 * The engine implements SOCKS5 only; `SOCKS4`/`HTTP` exist so old settings files still decode.
 */
@Serializable
enum class ProxyType { NONE, SOCKS4, SOCKS5, HTTP }

@Serializable
data class ConnectionSettings(
    val listenPort: Int = 6881,
    val useRandomPort: Boolean = false,
    val maxConnections: Int = 500,
    val maxConnectionsPerTorrent: Int = 100,
    /** Global upload slots: concurrent unchoked peers. */
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
    /**
     * Resolve through the built-in resolver (DNSSEC validation, QNAME minimisation, root walk when
     * no upstream is set) instead of the OS one; it also feeds the guard that refuses loopback and
     * cloud-metadata fetches. Off needs no third party but requires outbound UDP/53.
     */
    val enableDoh: Boolean = true,
    /**
     * Forwarders in priority order, one per line: `scheme://address[/path][#tls-name]`, e.g.
     * `tls://1.1.1.1#one.one.one.one` or a bare `223.5.5.5`. The address must be an IP literal (a
     * resolver cannot resolve its own name); well-known provider hostnames are mapped automatically.
     */
    val dohProviders: String = DEFAULT_DOH_PROVIDERS,
    /** Off on a v4-only network: an AAAA lookup is a wasted round trip and v6 peers are unreachable. */
    val enableIpv6: Boolean = true,
    /** Allow fetches that resolve to a private LAN address; off refuses every RFC 1918 / ULA target. */
    val allowLanWebseeds: Boolean = true,
)

/** Mirrors the ladder in `native/src/dns.rs`; IP-literal, TLS name after `#`. */
const val DEFAULT_DOH_PROVIDERS: String =
    "https://1.1.1.1/dns-query#cloudflare-dns.com\n" +
        "https://223.5.5.5/dns-query#dns.alidns.com\n" +
        "https://1.12.12.12/dns-query#doh.pub"


@Serializable
enum class ScheduleDays { EVERY_DAY, WEEKDAYS, WEEKEND, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

@Serializable
data class SpeedSettings(
    /** Global limits in KiB/s, 0 = unlimited; the alt* pair applies inside the schedule window. */
    val globalDownloadLimitKib: Long = 0,
    val globalUploadLimitKib: Long = 0,
    val altDownloadLimitKib: Long = 10240,
    val altUploadLimitKib: Long = 3072,
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

@Serializable
enum class EncryptionMode { ALLOW, PREFER, REQUIRE }

@Serializable
enum class ContentLayout { ORIGINAL, SUBFOLDER, NO_SUBFOLDER }

/**
 * OFF: files grow as pieces land (most fragmentation). SPARSE: the extent is reserved up front
 * while space is consumed only as data lands. FULL: reserved and physically written out.
 */
@Serializable
enum class PreallocationMode(val label: String, val code: Int) {
    OFF("关闭", 0),
    SPARSE("稀疏（推荐）", 1),
    FULL("完整", 2);

    companion object {
        fun fromCode(code: Int): PreallocationMode =
            entries.firstOrNull { it.code == code } ?: SPARSE
    }
}

@Serializable
data class BitTorrentSettings(
    val enableDht: Boolean = true,
    val enablePex: Boolean = true,
    val enableLsd: Boolean = true,
    val encryptionMode: EncryptionMode = EncryptionMode.PREFER,
    val enableUpnp: Boolean = true,
    val enableNatPmp: Boolean = true,
    val maxPeersPerTorrent: Int = 80,
    /** Blocks in flight per peer (16 KiB blocks each); 32 = up to 512 KiB per peer. */
    val requestPipeline: Int = 32,
    /** A block not answered within this window is released and re-issued to a healthier peer. */
    val requestTimeoutMs: Long = 20_000,
    /** Consecutive timeouts before a peer is banned; the counter resets on ANY delivered block. */
    val maxRequestTimeouts: Int = 8,
    val endgamePieces: Int = 32,
    val smartScheduling: Boolean = true,
    val useDefaultTrackers: Boolean = true,
    val antiLeechEnabled: Boolean = true,
    /** Hard anti-leech: known leech clients (Xunlei, Thunder, FlashGet, …) are never unchoked. */
    val blockLeechClients: Boolean = true,
    /** Announce URLs, one per line. */
    val extraTrackers: String = "",
    /** Must answer a trackerslist document; the result feeds [subscribedTrackers] for every torrent. */
    val trackerUpdateUrl: String = "",
    /** Hours between refreshes; 0 = manual only. */
    val trackerUpdateHours: Int = 12,
    /** Epoch millis of the last successful fetch; 0 = never. */
    val trackerUpdateLastMs: Long = 0,
    val subscribedTrackers: String = "",
    /** Write-back cache budget in bytes. */
    val cacheBytes: Long = 256L * 1024 * 1024,
    /** 0=off, 1=sparse, 2=full; applied when files are first opened, so only new torrents. */
    val preallocation: Int = 1,
    val seedingSlots: Int = 8,
    val leechingSlots: Int = 8,
    val optimisticIntervalMs: Long = 30_000,
    val snubTimeoutMs: Long = 60_000,
    val chokeIntervalMs: Long = 10_000,
    val schedulerAlpha: Int = 8,
    val schedulerBeta: Int = 2,
    val schedulerGamma: Int = 1,
    val schedulerDelta: Int = 64,
    val schedulerEdgeBytes: Long = 4L * 1024 * 1024,
    val contentLayout: ContentLayout = ContentLayout.ORIGINAL,
    val anonymousMode: Boolean = false,
) {
    /** [extraTrackers] plus the last subscription result, so config, tracker merges and WebUI agree. */
    val allTrackers: String
        get() =
            when {
                subscribedTrackers.isBlank() -> extraTrackers
                extraTrackers.isBlank() -> subscribedTrackers
                else -> extraTrackers.trimEnd() + "\n" + subscribedTrackers.trim()
            }
}

@Serializable
data class WebUiSettings(
    /** Desktop/NAS builds; the phone app ignores it. */
    val enabled: Boolean = true,
    val port: Int = 18881,
    val username: String = "admin",
    /** PBKDF2-HMAC-SHA256 hash (`pbkdf2$…`) — never the plaintext. */
    val passwordHash: String = "",
    val hostHeaderValidation: Boolean = false,
    val httpsEnabled: Boolean = false,
    /** Desktop only: also listen on the LAN instead of loopback (headless binds `--bind=0.0.0.0`). */
    val remoteAccess: Boolean = false,
    val maxAuthFailCount: Int = 5,
    val banDurationSec: Long = 3600,
    val sessionTimeoutMinutes: Long = 60,
    val csrfProtection: Boolean = true,
    val clickjackingProtection: Boolean = true,
    val localHostAuth: Boolean = true,
    val reverseProxyEnabled: Boolean = false,
)

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
