package com.typebit.platform

import android.os.Environment
import com.typebit.AppContextHolder
import java.net.ServerSocket

actual object Platform {
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
}
