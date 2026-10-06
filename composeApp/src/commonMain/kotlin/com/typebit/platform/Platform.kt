package com.typebit.platform

/**
 * Thin platform seam. Everything the shared code needs that differs between Android and desktop
 * lives behind these functions — the rest of the app is pure Kotlin and never touches platform APIs
 * directly.
 */
expect object Platform {
    /** "Android", "Windows", "macOS", "Linux". */
    val name: String

    val isDesktop: Boolean

    /** Per-user app data directory (created on demand). */
    fun appDataDir(): String

    /** The default "Downloads" directory (always app-writable). */
    fun defaultDownloadDir(): String

    /**
     * Resolves a save directory the engine can actually write to.
     *
     * `preferred` is the user's configured path; when it is blank, missing or
     * NOT writable by this process (Android scoped storage denies direct
     * access to public paths such as `/storage/emulated/0/Download` without
     * `MANAGE_EXTERNAL_STORAGE`), the platform default is used instead of
     * letting every piece write fail with EACCES.
     */
    fun resolveSaveDir(preferred: String): String

    /** An OS-assigned free TCP port (for "random port" mode). */
    fun findFreePort(): Int

    /** Whether a system tray is available (desktop only). */
    fun isTraySupported(): Boolean

    fun ensureBackgroundMode(active: Boolean)

    fun backgroundModeEnabled(): Boolean

    fun batteryOptimizationExempt(): Boolean

    /** Opens the system dialog to exempt the app from battery optimization. */
    fun openBatteryOptimizationSettings()
}
