package com.typebit.platform

import android.content.Intent
import android.net.Uri
import com.typebit.AppContextHolder

actual fun openInBrowser(url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppContextHolder.context.startActivity(intent)
    }
}
