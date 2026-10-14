package com.typebit.platform

import android.os.Environment
import android.os.PowerManager
import com.typebit.AppContextHolder
import com.typebit.download.DownloadService
import java.io.File
import java.net.ServerSocket

actual object Platform {
    private var backgroundServiceUp = false

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    actual val name: String = "Android"

    actual val isDesktop: Boolean = false

    actual fun appDataDir(): String =
        AppContextHolder.context.filesDir.absolutePath

    /** `getExternalFilesDir(DIRECTORY_DOWNLOADS)`: public `Downloads/` is not writable since Android 10
     *  scoped storage (no permission is even declared), while this directory needs none and the
     *  FileProvider roots already cover it. */
    actual fun defaultDownloadDir(): String {
        val ctx = AppContextHolder.context
        val external = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val base = external ?: File(ctx.filesDir, "Download")
        val dir = File(base, "TypeBitTorrent")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    actual fun resolveSaveDir(preferred: String): String {
        val trimmed = preferred.trim()
        if (trimmed.isNotEmpty() && isWritableDir(File(trimmed))) return File(trimmed).absolutePath
        return defaultDownloadDir()
    }

    private fun isWritableDir(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                false
            } else {
                val probe = File(dir, ".typebit_write_probe")
                probe.writeText("")
                probe.delete()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    actual fun findFreePort(): Int =
        ServerSocket(0).use { it.localPort }

    actual fun isTraySupported(): Boolean = false

    actual fun ensureBackgroundMode(active: Boolean) {
        // Idempotent: keep exactly one service up while any transfer is live.
        if (active && !backgroundServiceUp) {
            backgroundServiceUp = true
            DownloadService.start(AppContextHolder.context)
            acquireMulticastLock()
        } else if (!active && backgroundServiceUp) {
            backgroundServiceUp = false
            DownloadService.stop(AppContextHolder.context)
            releaseMulticastLock()
        }
    }

    /** Acquire the WiFi multicast lock before the engine opens its UDP sockets: on several OEM ROMs a lock
     *  taken afterwards does not retroactively enable multicast. Idempotent. */
    fun ensureLsdMulticastEarly() {
        acquireMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi =
                AppContextHolder.context.getSystemService(android.content.Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager
            if (multicastLock == null) {
                multicastLock = wifi.createMulticastLock("typebit-lsd").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
            // Not on WiFi: LSD cannot receive, but DHT/trackers still work.
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    actual fun backgroundModeEnabled(): Boolean {
        return batteryOptimizationExempt() && backgroundServiceUp
    }

    actual fun batteryOptimizationExempt(): Boolean {
        val ctx = AppContextHolder.context
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    actual fun openBatteryOptimizationSettings() {
        val ctx = AppContextHolder.context
        if (batteryOptimizationExempt()) return
        // The canonical "allow" dialog is API 23+; some OEMs (ColorOS…) drop the action, so fall back to
        // the app-details page where the user can flip the switch by hand.
        val request =
                android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${ctx.packageName}"),
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        var launched = false
        try {
            ctx.startActivity(request)
            launched = true
        } catch (_: Exception) {
        }
        if (!launched) {
            try {
                val details =
                        android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${ctx.packageName}"),
                        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(details)
            } catch (_: Exception) {
            }
        }
    }
}
