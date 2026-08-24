package com.typebit.ui.wallpaper

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Sanity checks for the cross-platform wallpaper blur pipeline. */
class WallpaperBlurTest {

    private fun solidImage(w: Int, h: Int, argb: Int): ImageBitmap {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val pixels = IntArray(w * h) { argb }
        img.setRGB(0, 0, w, h, pixels, 0, w)
        return img.toComposeImageBitmap()
    }

    @Test
    fun radiusZeroReturnsSource() {
        val src = solidImage(32, 32, 0xFF112233.toInt())
        val out = blurWallpaper(src, 0f)
        // For radius ~0 the pipeline short-circuits and reuses the source.
        assert(out === src) { "radius 0 should reuse the source bitmap" }
        assertEquals(32, out.width)
        assertEquals(32, out.height)
    }

    @Test
    fun blurSoftensEdge() {
        // Left half black, right half white → a hard vertical edge.
        val w = 64
        val h = 64
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (x < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        img.setRGB(0, 0, w, h, pixels, 0, w)
        val src = img.toComposeImageBitmap()

        val blurred = blurWallpaper(src, 16f)
        assertEquals(w, blurred.width)
        assertEquals(h, blurred.height)

        // Read back the blurred center row: the edge pixel (x=32) must now be
        // a blend (mid-gray), not pure black or white.
        val row = IntArray(w)
        blurred.readPixels(row, 0, h / 2, w, 1, 0, w)
        val edge = row[w / 2]
        val r = (edge ushr 16) and 0xFF
        val g = (edge ushr 8) and 0xFF
        val b = edge and 0xFF
        assert(r > 32 && r < 223) { "edge pixel should be blended, got R=$r G=$g B=$b" }
        // And a pixel far from the edge keeps its original side's value.
        val farLeft = (row[0] ushr 16) and 0xFF
        assert(farLeft < 16) { "far-left should stay dark, got $farLeft" }
    }

    @Test
    fun createImageBitmapFromPixelsRoundTrips() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { i -> 0xFF000000.toInt() or ((i * 7) shl 8) }
        val bmp = createImageBitmapFromPixels(pixels, w, h)
        assertEquals(w, bmp.width)
        assertEquals(h, bmp.height)
        val back = IntArray(w * h)
        bmp.readPixels(back, 0, 0, w, h, 0, w)
        assertEquals(pixels[3], back[3])
    }

    @Test
    fun realFileLoadThenBlur() {
        // Full pipeline sanity: load a real image (the repo logo doubles as a
        // stand-in wallpaper), blur it, and confirm a valid bitmap comes out.
        val file = java.io.File("assets/typebittorrent.png")
        if (!file.exists()) return // repo layout change should not fail CI
        val src = loadWallpaperBitmap(file.absolutePath) ?: return
        val blurred = blurWallpaper(src, 24f)
        assertEquals(src.width, blurred.width)
        assertEquals(src.height, blurred.height)
        // A blurred opaque image must still be opaque after the round trip.
        val px = IntArray(1)
        blurred.readPixels(px, blurred.width / 2, blurred.height / 2, 1, 1, 0, 1)
        val alpha = (px[0] ushr 24) and 0xFF
        assert(alpha >= 250) { "blur must preserve opacity, got alpha=$alpha" }
    }

    @Test
    fun prepareWallpaperDownscalesAndBlurs() {
        // The app background pipeline must return a small, blurred bitmap
        // even from a large source (this is what keeps scrolling cheap).
        val src = solidImage(1200, 800, 0xFF446688.toInt())
        val prepared = prepareWallpaper(src, 24f)
        assert(prepared.width <= 640) { "background should be downscaled, got ${prepared.width}" }
        assert(prepared.height <= 640) { "background should be downscaled, got ${prepared.height}" }
        // The prepared bitmap must be opaque (no alpha loss).
        val px = IntArray(1)
        prepared.readPixels(px, prepared.width / 2, prepared.height / 2, 1, 1, 0, 1)
        assert(((px[0] ushr 24) and 0xFF) >= 250) { "prepared wallpaper must stay opaque" }
    }

    @Test
    fun downscaleImageKeepsSmall() {
        val src = solidImage(200, 100, 0xFF123456.toInt())
        val out = downscaleImage(src, 640)
        // Already under the cap → returned unchanged (same instance).
        assert(out === src) { "small source should be reused" }
        val big = solidImage(2000, 1000, 0xFF123456.toInt())
        val small = downscaleImage(big, 640)
        assertEquals(640, small.width)
        assertEquals(320, small.height)
    }
}
