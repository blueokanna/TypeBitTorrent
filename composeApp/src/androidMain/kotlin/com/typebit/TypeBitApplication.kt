package com.typebit

import android.app.Application
import com.typebit.platform.Platform

/**
 * Holds the application context so shared (platform-agnostic) code can reach
 * Android-specific paths without threading a Context through every call.
 */
class TypeBitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.context = applicationContext
        // LSD (BEP-14) LAN discovery: acquire the WiFi multicast lock NOW,
        // before the engine thread creates its UDP sockets. A lock taken
        // only after socket creation does not retroactively enable multicast
        // on several OEM ROMs, which would break same-router peer discovery.
        Platform.ensureLsdMulticastEarly()
    }
}

object AppContextHolder {
    lateinit var context: android.content.Context
        internal set
}
