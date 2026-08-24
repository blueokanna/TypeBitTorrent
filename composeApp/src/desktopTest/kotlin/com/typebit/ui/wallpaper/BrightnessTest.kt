package com.typebit.ui.wallpaper

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the auto-contrast luminance estimator that drives the DIM scrim.
 */
class BrightnessTest {

    private fun solidBitmap(color: Color): ImageBitmap {
        val bmp = ImageBitmap(64, 64)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { this.color = color }
        canvas.drawRect(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(64f, 64f)), paint)
        return bmp
    }

    @Test
    fun pureWhite_isBright() {
        val b = averageBrightness(solidBitmap(Color.White))
        assertTrue("expected bright, got $b", b > 0.9f)
    }

    @Test
    fun pureBlack_isDark() {
        val b = averageBrightness(solidBitmap(Color.Black))
        assertTrue("expected dark, got $b", b < 0.05f)
    }

    @Test
    fun midGray_aroundHalf() {
        val b = averageBrightness(solidBitmap(Color.Gray))
        // WCAG luminance of 128 gray ≈ 0.216.
        assertTrue("expected ~0.2, got $b", b in 0.15f..0.30f)
    }
}
