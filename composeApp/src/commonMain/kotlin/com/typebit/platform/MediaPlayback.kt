package com.typebit.platform

// 边下边播 seam. The path may be a still-downloading `<name>.part`; the streaming scheduler verifies pieces
// head-first and then in file order, so playback can start as soon as the file's head has landed.
expect fun playMediaFile(path: String): Boolean

fun isVideoFile(name: String): Boolean = extensionOf(name) in VIDEO_EXTENSIONS

fun isMediaFile(name: String): Boolean {
    val ext = extensionOf(name)
    return ext in VIDEO_EXTENSIONS || ext in AUDIO_EXTENSIONS
}

private fun extensionOf(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    if (base.isEmpty() || base.startsWith(".") && base.count { it == '.' } == 1) return ""
    return base.substringAfterLast('.', "").lowercase()
}

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "webm", "mov", "m4v",
    "ts", "flv", "wmv", "mpg", "mpeg", "rmvb", "rm", "3gp", "ogv",
)

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma", "ape",
)
