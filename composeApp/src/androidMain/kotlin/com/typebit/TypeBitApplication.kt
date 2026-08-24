package com.typebit

import android.app.Application

/**
 * Holds the application context so shared (platform-agnostic) code can reach
 * Android-specific paths without threading a Context through every call.
 */
class TypeBitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.context = applicationContext
    }
}

object AppContextHolder {
    lateinit var context: android.content.Context
        internal set
}
