package jp.project2by2.musicplayer.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.imageResource
import jp.project2by2.musicplayer.resources.Res
import jp.project2by2.musicplayer.resources.logo_image
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun PlayerLogo() {
    // The 512x211 bitmap is shrunk about 4x at 48dp on a 1x desktop display. Scaling it while drawing
    // leaves jagged edges there (the GPU renderer ignores mipmap sampling), so resample it once to the
    // exact pixel size with Lanczos3 (smooth but still sharp) and draw that 1:1.
    val source = imageResource(Res.drawable.logo_image)
    val density = LocalDensity.current
    val heightPx = with(density) { 48.dp.roundToPx() }
    val widthPx = (source.width * heightPx / source.height.toFloat()).roundToInt()
    val logo = remember(source, widthPx, heightPx) { source.alphaMaskResizedTo(widthPx, heightPx) }
    Image(logo, playerString("app_logo"),
        modifier = with(density) { Modifier.size(widthPx.toDp(), heightPx.toDp()) },
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
}

/** The logo is a single-color shape that is tinted anyway, so only its alpha is resampled (to white). */
private fun ImageBitmap.alphaMaskResizedTo(dstWidth: Int, dstHeight: Int): ImageBitmap {
    val argb = IntArray(width * height).also { readPixels(it) }
    val alpha = FloatArray(argb.size) { (argb[it] ushr 24) / 255f }
    val horizontal = resample(alpha, width, height, dstWidth, horizontal = true)
    val both = resample(horizontal, dstWidth, height, dstHeight, horizontal = false)
    val pixels = IntArray(both.size) { (both[it].coerceIn(0f, 1f) * 255f).roundToInt() shl 24 or 0xFFFFFF }
    return imageBitmapFromArgb(dstWidth, dstHeight, pixels)
}

/** Separable Lanczos3 pass along one axis of a [width] x [height] plane, resizing that axis to [dstLength]. */
private fun resample(src: FloatArray, width: Int, height: Int, dstLength: Int, horizontal: Boolean): FloatArray {
    val srcLength = if (horizontal) width else height
    val lines = if (horizontal) height else width
    val outWidth = if (horizontal) dstLength else width
    val scale = srcLength.toDouble() / dstLength
    val stretch = max(scale, 1.0)
    val support = 3 * stretch
    val out = FloatArray(outWidth * (if (horizontal) height else dstLength))
    for (d in 0 until dstLength) {
        val center = (d + 0.5) * scale
        val first = floor(center - support).toInt()
        val last = ceil(center + support).toInt()
        val weights = DoubleArray(last - first + 1) { lanczos3((first + it + 0.5 - center) / stretch) }
        val total = weights.sum()
        for (line in 0 until lines) {
            var sum = 0.0
            for (k in weights.indices) {
                val s = (first + k).coerceIn(0, srcLength - 1)
                sum += weights[k] * src[if (horizontal) line * width + s else s * width + line]
            }
            out[if (horizontal) line * outWidth + d else d * outWidth + line] = (sum / total).toFloat()
        }
    }
    return out
}

private fun lanczos3(x: Double): Double = when {
    x == 0.0 -> 1.0
    abs(x) >= 3 -> 0.0
    else -> (PI * x).let { 3 * sin(it) * sin(it / 3) / (it * it) }
}

/** Builds an image from unpremultiplied ARGB pixels. */
internal expect fun imageBitmapFromArgb(width: Int, height: Int, pixels: IntArray): ImageBitmap
