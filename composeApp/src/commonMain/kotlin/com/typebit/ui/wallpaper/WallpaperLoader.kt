package com.typebit.ui.wallpaper

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Loads a wallpaper image by path into an [ImageBitmap].
 *
 * - Desktop: file path (AWT image IO).
 * - Android: a file copied into app-private storage at pick time (the SAF
 *   content URI is not stable across sessions, so we always copy to disk).
 * Returns null when absent/unreadable — the app then falls back to no
 * wallpaper and the static palette.
 */
expect fun loadWallpaperBitmap(path: String?): ImageBitmap?
