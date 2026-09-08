package jp.project2by2.musicplayer.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import jp.project2by2.musicplayer.albumArtworkFileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

/** OS-specific file access/decoding. All image presentation remains in shared Composables. */
class DesktopArtwork {
    private data class Key(val path: String, val length: Long, val modified: Long)
    private val cache = object : LinkedHashMap<Key, ImageBitmap>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ImageBitmap>?) = size > 24
    }

    @Synchronized
    fun load(folder: File): ImageBitmap? {
        val files = folder.listFiles().orEmpty().filter { it.isFile }
        val candidates = albumArtworkFileNames.flatMap { name -> files.filter { it.name.equals(name, ignoreCase = true) }.sortedBy { it.name } }.distinct()
        for (file in candidates) {
            val bitmap = runCatching {
                val key = Key(file.canonicalPath, file.length(), file.lastModified())
                cache[key]?.let { return it }
                decode(file)?.also { cache[key] = it }
            }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return null
    }

    private fun decode(file: File): ImageBitmap? = ImageIO.createImageInputStream(file)?.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return@use null
        val reader = readers.next()
        try {
            reader.input = input
            // Read a bounded thumbnail rather than allocating full-resolution folder artwork.
            val sample = ceil(max(reader.getWidth(0), reader.getHeight(0)) / 768.0).toInt().coerceAtLeast(1)
            val options = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
            val decoded = reader.read(0, options)
            try { decoded.toComposeImageBitmap() } finally { decoded.flush() }
        } finally { reader.dispose() }
    }
}

@Composable
fun rememberDesktopArtwork(loader: DesktopArtwork, folder: File?): ImageBitmap? = key(folder?.absolutePath) {
    val image by produceState<ImageBitmap?>(null, loader, folder?.absolutePath) {
        value = withContext(Dispatchers.IO) { folder?.let { runCatching { loader.load(it) }.getOrNull() } }
    }
    image
}
