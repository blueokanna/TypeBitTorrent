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
}
