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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Draws the wallpaper as the app background: the image fills the window,
 * is dimmed by a [scrimColor] overlay. The blur is applied UPSTREAM — the
 * caller passes an already-blurred [bitmap] produced once, off the UI
 * thread, by [blurWallpaper]. Keeping blur out of this layer means drawing
 * it on every frame (route transitions, scrolling, slider drags) is a cheap
 * static blit instead of a full-screen GPU re-blur — this is what keeps the
 * UI smooth while wallpaper settings are adjusted.
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
                // Max vertical pan ≈ the full container height (0.95), so
                // the slider's 100% exposes the top of the image that Crop
                // would otherwise cut off.
                val maxPanRatio = 0.95f
                val panPx = with(density) { (maxHeight * maxPanRatio).toPx() }
                // Crop draws the image scaled up by (1 + 2*maxPanRatio) so a
                // full pan never reveals an edge.
                val cropScale = 1f + 2f * maxPanRatio
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (contentScale == ContentScale.Crop) {
                                scaleX = cropScale
                                scaleY = cropScale
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
