package jp.project2by2.musicplayer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

class MidiParser(private val contentResolver: ContentResolver) {
    fun getMetadata(uri: Uri): MidiMetadata {
        val uriKey = cacheKeyForUri(uri)
        MetadataLruCache.get(uriKey)?.let { return it }
        val fileName = resolveDisplayName(uri)?.takeIf { it.isNotBlank() }
        fileName?.let { MetadataLruCache.get(cacheKeyForFileName(it))?.let { cached -> return cached } }

        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return EMPTY_METADATA
        val metadata = parseMetadata(bytes)
        MetadataLruCache.put(uriKey, metadata)
        fileName?.let { MetadataLruCache.put(cacheKeyForFileName(it), metadata) }
        return metadata
    }

    fun getMetadataFromFile(file: File): MidiMetadata {
        val fileSignatureKey = cacheKeyForFileSignature(file)
        MetadataLruCache.get(fileSignatureKey)?.let { return it }

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return EMPTY_METADATA
        val metadata = parseMetadata(bytes)
        MetadataLruCache.put(fileSignatureKey, metadata)
        return metadata
    }

    fun parseMetadata(bytes: ByteArray): MidiMetadata = parseMidiMetadata(bytes)

    private companion object {
        private const val CACHE_MAX_ENTRIES = 512

        val EMPTY_METADATA = MidiMetadata(
            title = null,
            copyright = null,
            loopPointMs = null,
            durationMs = null
        )

        private fun cacheKeyForUri(uri: Uri): String = "uri:${uri}"
        private fun cacheKeyForFileName(fileName: String): String = "name:${fileName.lowercase()}"
        private fun cacheKeyForFileSignature(file: File): String {
            return "file:${file.absolutePath.lowercase()}:${file.length()}:${file.lastModified()}"
        }

        private object MetadataLruCache {
            private val map = object : LinkedHashMap<String, MidiMetadata>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MidiMetadata>?): Boolean {
                    return size > CACHE_MAX_ENTRIES
                }
            }

            @Synchronized
            fun get(key: String): MidiMetadata? = map[key]

            @Synchronized
            fun put(key: String, value: MidiMetadata) {
                map[key] = value
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }
}
