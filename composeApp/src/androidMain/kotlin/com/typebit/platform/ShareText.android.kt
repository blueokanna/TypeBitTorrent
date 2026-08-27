package com.typebit.platform

import android.content.Intent
import com.typebit.AppContextHolder

/** Android actual: system share sheet via `ACTION_SEND`. */
actual fun shareText(subject: String, text: String) {
    val ctx = AppContextHolder.context
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, "分享")
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(chooser) }
}
