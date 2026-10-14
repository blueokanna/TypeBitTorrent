package com.typebit.ui.wallpaper

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Loads a wallpaper by path: desktop a file path, Android a copy taken at pick time
 * (SAF content URIs are not stable across sessions); null when absent/unreadable.
 */
expect fun loadWallpaperBitmap(path: String?): ImageBitmap?
