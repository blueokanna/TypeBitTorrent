package com.typebit.ui.util

object Format {

    private val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        var v = value.toDouble()
        var i = 0
        while (v >= 1024.0 && i < UNITS.size - 1) {
            v /= 1024.0
            i++
        }
        return if (i == 0) "${v.toLong()} ${UNITS[i]}" else String.format("%.2f %s", v, UNITS[i])
    }

    fun speed(bytesPerSec: Long): String =
        if (bytesPerSec <= 0) "0 B/s" else "${bytes(bytesPerSec)}/s"

/** The download target size: with files skipped, show only the selected bytes
 * (a partial selection must not read as a full-torrent download). */
    fun targetSize(selected: Long, total: Long): String =
        if (selected in 1 until total) bytes(selected) else bytes(total)

    fun targetSizeFull(selected: Long, total: Long): String =
        if (selected in 1 until total) "${bytes(selected)} / ${bytes(total)}" else bytes(total)

    fun percent(p: Double): String = "${(p * 100).coerceIn(0.0, 100.0).let { String.format("%.1f", it) }}%"

    fun eta(seconds: Long?): String {
        if (seconds == null) return "∞"
        if (seconds <= 0) return "0 秒"
        val s = seconds
        return when {
            s < 60 -> "${s} 秒"
            s < 3600 -> "${s / 60} 分钟"
            s < 86400 -> "${s / 3600} 小时 ${(s % 3600) / 60} 分钟"
            else -> "${s / 86400} 天 ${(s % 86400) / 3600} 小时"
        }
    }

    fun ratio(r: Double): String = if (r <= 0) "0.00" else String.format("%.2f", r)

    fun count(v: Int): String = v.toString()

    fun bytesDetailed(value: Long): String {
        val human = bytes(value)
        return if (value >= 1024) "$human (${
            String.format("%,d", value).replace(',', ',')
        } 字节)" else human
    }
}
