package com.typebit.platform

import androidx.compose.runtime.Composable

// Platform wallpaper picker: `onPicked` gets a path that stays valid across launches — Android copies the
// SAF pick into app-private storage first (content URIs expire).
@Composable
expect fun rememberWallpaperPicker(onPicked: (String) -> Unit): () -> Unit
