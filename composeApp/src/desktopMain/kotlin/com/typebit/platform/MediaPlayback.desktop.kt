@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package com.typebit.platform

import java.awt.Desktop
import java.io.File

/** Handles the file, or its staged `.part` twin while downloading, with the OS default player. */
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
