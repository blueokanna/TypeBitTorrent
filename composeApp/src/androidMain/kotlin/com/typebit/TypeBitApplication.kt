package com.typebit

import android.app.Application
import com.typebit.platform.Platform

class TypeBitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.context = applicationContext
        // Take the WiFi multicast lock before the engine opens its UDP sockets (LSD/BEP-14): on several
        // OEM ROMs a lock acquired afterwards does not enable multicast.
        Platform.ensureLsdMulticastEarly()
    }
}

object AppContextHolder {
    lateinit var context: android.content.Context
        internal set
}
