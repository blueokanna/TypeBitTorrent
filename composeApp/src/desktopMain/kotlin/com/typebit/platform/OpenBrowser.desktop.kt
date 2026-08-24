package com.typebit.platform

import java.awt.Desktop
import java.net.URI

actual fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
