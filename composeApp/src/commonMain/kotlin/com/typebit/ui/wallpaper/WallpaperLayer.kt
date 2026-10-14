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
 * Wallpaper backdrop: the bitmap arrives pre-blurred (blurring here would re-blur every
 * frame). [scrimColor] dims it; [verticalOffsetRatio] (-1..1) pans and `Crop` scales by
 * (1 + 2 * maxPanRatio) so a pan never exposes an edge. A null [bitmap] paints
 * [backgroundColor] — desktop windows are transparent by default.
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
                val maxPanRatio = 0.95f
                val panPx = with(density) { (maxHeight * maxPanRatio).toPx() }
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
