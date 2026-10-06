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

    /**
     * `<home>/.typebit`, or the directory named by `-Dtypebit.data.dir` — the
     * knob the headless/NAS entry point sets so a container can keep settings,
     * resume data and receipts on a mounted `/config` volume.
     */
    actual fun appDataDir(): String {
        val override = System.getProperty("typebit.data.dir")?.takeIf { it.isNotBlank() }
        val dir = if (override != null) File(override) else File(System.getProperty("user.home"), ".typebit")
        FileIO.ensureDir(dir.absolutePath)
        return dir.absolutePath
    }

    actual fun defaultDownloadDir(): String =
        File(System.getProperty("user.home"), "Downloads").absolutePath

    actual fun resolveSaveDir(preferred: String): String {
        val trimmed = preferred.trim()
        if (trimmed.isNotEmpty()) {
            val dir = File(trimmed)
            try {
                if (dir.exists() || dir.mkdirs()) {
                    val probe = File(dir, ".typebit_write_probe")
                    probe.writeText("")
                    probe.delete()
                    return dir.absolutePath
                }
            } catch (_: Exception) {
                // Unusable (read-only mount, permission denied) → fall through.
            }
        }
        return defaultDownloadDir()
    }

    actual fun findFreePort(): Int =
        ServerSocket(0).use { it.localPort }

    actual fun isTraySupported(): Boolean = true

    actual fun ensureBackgroundMode(active: Boolean) {
    }

    actual fun backgroundModeEnabled(): Boolean = true

    actual fun batteryOptimizationExempt(): Boolean = true

    actual fun openBatteryOptimizationSettings() {
    }
}
