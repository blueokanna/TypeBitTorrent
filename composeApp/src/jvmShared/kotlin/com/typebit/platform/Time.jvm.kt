package com.typebit.platform

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun formatDateTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
