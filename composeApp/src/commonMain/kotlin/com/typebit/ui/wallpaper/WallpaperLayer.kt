package com.typebit.ui.wallpaper

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Draws the wallpaper as the app background: the image fills the window,
 * is gaussian-blurred, and is dimmed by a [scrimColor] overlay. The dim
 * level is chosen so foreground text keeps enough contrast regardless of
 * the image — this is what makes the wallpaper usable behind a full UI,
 * not just a lock screen.
 *
 * Display control:
 * - [contentScale] `Crop` fills the window (may cut edges); `Fit` shows the
 *   whole image (letterboxes with [backgroundColor]).
 * - [verticalOffsetRatio] (-1..1) pans the image vertically. In `Crop` the
 *   image is drawn slightly larger so the pan never exposes an edge; in
 *   `Fit` the pan is ignored to avoid gaps.
 *
 * [scrimColor] is black in dark themes (keeps white text readable) and
 * white in light themes (keeps black text readable). When [bitmap] is null
 * the layer paints [backgroundColor] instead — this is the ONLY thing that
 * guarantees the whole window matches the theme (AMOLED = pure black);
 * Compose Desktop windows are transparent by default and would otherwise
 * show an unrelated gray.
 */
@Composable
fun WallpaperLayer(
    bitmap: ImageBitmap?,
    dimAmount: Float,
    blurRadiusPx: Float,
    scrimColor: Color = Color.Black,
    backgroundColor: Color = Color.Transparent,
    contentScale: ContentScale = ContentScale.Crop,
    verticalOffsetRatio: Float = 0f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(backgroundColor)) {
        if (bitmap != null) {
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // How far the image may pan (fraction of the container) —
                // only meaningful in Crop, where the image over-scans.
                val panDp = maxHeight * 0.15f
                val panPx = with(density) { panDp.toPx() }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(blurRadiusPx.dp)
                        .graphicsLayer {
                            // Crop: draw 130% so the vertical pan (max 15% of
                            // the container height) never reveals an edge.
                            if (contentScale == ContentScale.Crop) {
                                scaleX = 1.30f
                                scaleY = 1.30f
                            }
                            translationY = panPx * verticalOffsetRatio.coerceIn(-1f, 1f)
                        },
                    contentScale = contentScale,
                )
            }
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
