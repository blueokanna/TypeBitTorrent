package com.typebit.platform

import android.os.Environment
import android.os.PowerManager
import com.typebit.AppContextHolder
import com.typebit.download.DownloadService
import java.net.ServerSocket

actual object Platform {
    private var backgroundServiceUp = false

    // Android's WiFi driver drops multicast by default — without a
    // MulticastLock the engine's LSD (BEP-14) announces and LAN peer
    // replies never arrive, so "download from my PC on the LAN" silently
    // fails. Held exactly while any transfer is live (same window as the
    // foreground service).
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    actual val name: String = "Android"

    actual val isDesktop: Boolean = false

    actual fun appDataDir(): String =
        AppContextHolder.context.filesDir.absolutePath

    actual fun defaultDownloadDir(): String {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return downloads?.absolutePath ?: AppContextHolder.context.filesDir.absolutePath
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
            // Not on WiFi / driver quirk: LSD just won't receive — the
            // engine still announces out and DHT/Tracker keep working.
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
            // Already released / not held.
        }
        multicastLock = null
    }

    actual fun backgroundModeEnabled(): Boolean {
        // True when the app is exempt from battery restrictions AND the
        // foreground service is up.
        val ctx = AppContextHolder.context
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        return exempt && backgroundServiceUp
    }

    actual fun openBatteryOptimizationSettings() {
        val ctx = AppContextHolder.context
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
        try {
            val intent =
                    android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:${ctx.packageName}"),
                    )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (_: Exception) {
            // OEMs (e.g. OPPO/ColorOS) may strip this action; fall back to
            // the app details page where the user can set it manually.
            try {
                val intent =
                        android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${ctx.packageName}"),
                        )
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (_: Exception) {
                // No settings screen available — nothing more we can do.
            }
        }
    }
}
