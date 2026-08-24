package com.typebit.ui.wallpaper

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Android: hand the ARGB buffer straight to `Bitmap.createBitmap`. */
actual fun createImageBitmapFromPixels(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
