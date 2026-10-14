package com.typebit.platform

expect object Platform {
    val name: String

    val isDesktop: Boolean

    fun appDataDir(): String

    fun defaultDownloadDir(): String

    // A blank, missing or unwritable `preferred` falls back to the platform default instead of letting
    // every piece write fail with EACCES (Android scoped storage denies public paths like `Downloads/`).
    fun resolveSaveDir(preferred: String): String

    fun findFreePort(): Int

    fun isTraySupported(): Boolean

    fun ensureBackgroundMode(active: Boolean)

    fun backgroundModeEnabled(): Boolean

    fun batteryOptimizationExempt(): Boolean

    fun openBatteryOptimizationSettings()
}
