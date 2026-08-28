package com.typebit.platform

/**
 * 边下边播 (stream-while-downloading) seam.
 *
 * Hands a media file to the platform's player. The path may point at a
 * still-downloading `<name>.part` staged file — the engine's streaming
 * scheduler verifies pieces head-first then strictly in file order, so once
 * the file's head is downloaded the player can start and keep streaming the
 * body as it lands. Returns `false` when the file is missing or empty.
 */
expect fun playMediaFile(path: String): Boolean

/** True when a filename is a playable video (ts/avi/rmvb/wmv/mp4/…). */
fun isVideoFile(name: String): Boolean {
    val n = name.substringBeforeLast('.').let { it to name.substringAfterLast('.', "") }
    val lower = n.second.lowercase()
    return lower in VIDEO_EXTENSIONS && n.first.isNotEmpty()
}

/** Video container extensions the streaming scheduler treats as playback-first. */
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "webm", "mov", "m4v",
    "ts", "flv", "wmv", "mpg", "mpeg", "rmvb", "rm", "3gp", "ogv",
)
