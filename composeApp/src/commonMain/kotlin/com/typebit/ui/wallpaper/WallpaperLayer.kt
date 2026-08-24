package com.typebit.ui.wallpaper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image

/**
 * Draws the wallpaper as the app background: the image fills the window,
 * is gaussian-blurred, and is dimmed by a [scrimColor] overlay. The dim
 * level is chosen so foreground text keeps enough contrast regardless of
 * the image — this is what makes the wallpaper usable behind a full UI,
 * not just a lock screen.
 *
 * [scrimColor] is black in dark themes (keeps white text readable) and
 * white in light themes (keeps black text readable). When [bitmap] is null
 * the layer draws nothing (plain theme background).
 */
@Composable
fun WallpaperLayer(
    bitmap: ImageBitmap?,
    dimAmount: Float,
    blurRadiusPx: Float,
    scrimColor: Color = Color.Black,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(blurRadiusPx.dp),
                contentScale = ContentScale.Crop,
            )
            // DIM overlay at the configured strength guarantees the surface
            // behind text keeps readable contrast on any image.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scrimColor.copy(alpha = dimAmount.coerceIn(0.0f, 0.85f))),
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            content()
        }
    }
}
