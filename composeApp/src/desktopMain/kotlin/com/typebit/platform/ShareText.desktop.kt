package com.typebit.platform

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Desktop shares through the clipboard (there is no OS share sheet); failures are swallowed. */
actual fun shareText(subject: String, text: String) {
    runCatching {
        val sel = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
    }
}
