package com.typebit.ui.wallpaper

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

/** Desktop: `BufferedImage.setRGB` consumes ARGB ints directly. */
actual fun createImageBitmapFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, width, height, pixels, 0, width)
    return img.toComposeImageBitmap()
}
