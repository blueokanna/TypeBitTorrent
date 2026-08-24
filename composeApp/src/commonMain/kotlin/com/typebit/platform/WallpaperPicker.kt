package com.typebit.platform

import androidx.compose.runtime.Composable

/**
 * A platform wallpaper-image picker. Returns a launcher lambda; when the
 * user picks an image, `onPicked(path)` fires with a stable absolute path
 * the loader can read on any later launch.
 *
 * - Desktop: AWT `FileDialog`, returns the original file path.
 * - Android: SAF photo picker; the content is copied into app-private
 *   storage first (content URIs are not stable across sessions) and the
 *   copy's path is returned.
 */
@Composable
expect fun rememberWallpaperPicker(onPicked: (String) -> Unit): () -> Unit
