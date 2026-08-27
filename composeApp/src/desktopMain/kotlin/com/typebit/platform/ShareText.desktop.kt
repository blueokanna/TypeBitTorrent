package com.typebit.platform

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Desktop actual: there is no OS share sheet, so the text is copied to the
 * system clipboard (the standard "share" on desktop) and the caller surfaces
 * a confirmation. Failure is swallowed — clipboard access is best-effort.
 */
actual fun shareText(subject: String, text: String) {
    runCatching {
        val sel = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, null)
    }
}
