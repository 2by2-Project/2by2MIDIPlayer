package jp.project2by2.musicplayer.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal actual fun imageBitmapFromArgb(width: Int, height: Int, pixels: IntArray): ImageBitmap {
    val bytes = ByteArray(pixels.size * 4)
    pixels.forEachIndexed { i, p ->
        bytes[i * 4] = p.toByte()
        bytes[i * 4 + 1] = (p shr 8).toByte()
        bytes[i * 4 + 2] = (p shr 16).toByte()
        bytes[i * 4 + 3] = (p ushr 24).toByte()
    }
    return Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4)
        .toComposeImageBitmap()
}
