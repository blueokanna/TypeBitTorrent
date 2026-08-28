@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package com.typebit.platform

import java.awt.Desktop
import java.io.File

/**
 * Desktop: hand the file (or its `.part` staged twin while downloading) to
 * the OS default media player.
 */
actual fun playMediaFile(path: String): Boolean {
    val file = File(path)
    if (!file.exists() || file.length() == 0L) return false
    return try {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return false
        }
        Desktop.getDesktop().open(file)
        true
    } catch (_: Exception) {
        false
    }
}
