package jp.project2by2.musicplayer.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

internal actual fun imageBitmapFromArgb(width: Int, height: Int, pixels: IntArray): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
