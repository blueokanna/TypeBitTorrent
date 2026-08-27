package com.typebit.platform

import java.io.File
import java.net.ServerSocket

actual object Platform {
    actual val name: String = when {
        System.getProperty("os.name").contains("win", ignoreCase = true) -> "Windows"
        System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macOS"
        else -> "Linux"
    }

    actual val isDesktop: Boolean = true

    actual fun appDataDir(): String {
        val dir = File(System.getProperty("user.home"), ".typebit")
        FileIO.ensureDir(dir.absolutePath)
        return dir.absolutePath
    }

    actual fun defaultDownloadDir(): String =
        File(System.getProperty("user.home"), "Downloads").absolutePath

    actual fun findFreePort(): Int =
        ServerSocket(0).use { it.localPort }

    actual fun isTraySupported(): Boolean = true

    actual fun ensureBackgroundMode(active: Boolean) {
        // Desktop processes keep running while the window is open; nothing
        // extra is needed.
    }

    actual fun backgroundModeEnabled(): Boolean = true

    actual fun openBatteryOptimizationSettings() {
        // Desktop has no battery restrictions.
    }
}
