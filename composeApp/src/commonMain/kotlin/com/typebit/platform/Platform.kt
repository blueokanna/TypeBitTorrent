package com.typebit.platform

/**
 * Thin platform seam. Everything the shared code needs that differs between
 * Android and desktop lives behind these functions — the rest of the app is
 * pure Kotlin and never touches platform APIs directly.
 */
expect object Platform {
    /** "Android", "Windows", "macOS", "Linux". */
    val name: String

    val isDesktop: Boolean

    /** Per-user app data directory (created on demand). */
    fun appDataDir(): String

    /** The default "Downloads" directory. */
    fun defaultDownloadDir(): String

    /** An OS-assigned free TCP port (for "random port" mode). */
    fun findFreePort(): Int

    /** Whether a system tray is available (desktop only). */
    fun isTraySupported(): Boolean

    /**
     * Ensures background execution mode for live transfers. On Android this
     * runs a `dataSync` foreground service (plus a partial wake lock) so
     * downloads continue with the screen locked or the app backgrounded;
     * desktop needs nothing. `active` = at least one torrent is running.
     */
    fun ensureBackgroundMode(active: Boolean)

    /** Whether background transfers currently survive app/OS limits. */
    fun backgroundModeEnabled(): Boolean

    /** Opens the system dialog to exempt the app from battery optimization. */
    fun openBatteryOptimizationSettings()
}
