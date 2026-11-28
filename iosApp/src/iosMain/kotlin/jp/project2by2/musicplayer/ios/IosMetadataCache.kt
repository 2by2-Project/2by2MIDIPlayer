@file:OptIn(ExperimentalForeignApi::class)

package jp.project2by2.musicplayer.ios

import jp.project2by2.musicplayer.MidiMetadata
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSPropertyListBinaryFormat_v1_0
import platform.Foundation.NSPropertyListSerialization
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * Parsed titles, copyrights, loop points and durations kept between launches (Android's Room
 * metadata cache). Entries are tied to the file's size and modification time.
 */
class IosMetadataCache(private val file: String) {
    private data class Entry(val metadata: MidiMetadata, val size: Long, val modified: Double)
    private val entries = mutableMapOf<String, Entry>()
    private var dirty = false

    fun load() {
        val data = NSData.dataWithContentsOfFile(file) ?: return
        val root = NSPropertyListSerialization.propertyListWithData(data, 0u, null, null) as? Map<*, *> ?: return
        for ((key, value) in root) {
            val map = value as? Map<*, *> ?: continue
            val metadata = MidiMetadata(
                title = map["t"] as? String,
                copyright = map["c"] as? String,
                loopPointMs = (map["l"] as? Number)?.toLong(),
                durationMs = (map["d"] as? Number)?.toLong(),
            )
            entries[key as? String ?: continue] = Entry(metadata, (map["s"] as? Number)?.toLong() ?: -1, (map["m"] as? Number)?.toDouble() ?: 0.0)
        }
    }

    private fun signature(path: String): Pair<Long, Double>? {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return null
        val size = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return null
        val modified = (attributes[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0
        return size to modified
    }

    /** The cached value when the file at [path] has not changed since it was parsed. */
    fun get(key: String, path: String): MidiMetadata? {
        val entry = entries[key] ?: return null
        val (size, modified) = signature(path) ?: return null
        return entry.metadata.takeIf { entry.size == size && entry.modified == modified }
    }

    fun put(key: String, path: String, metadata: MidiMetadata) {
        val (size, modified) = signature(path) ?: return
        entries[key] = Entry(metadata, size, modified)
        dirty = true
    }

    /** Writes the cache when something changed; kept to the 20,000 most recent folders' songs at most. */
    fun save() {
        if (!dirty) return
        dirty = false
        val root = entries.entries.toList().takeLast(20000).associate { (key, entry) ->
            key to buildMap<String, Any> {
                entry.metadata.title?.let { put("t", it) }
                entry.metadata.copyright?.let { put("c", it) }
                entry.metadata.loopPointMs?.let { put("l", it) }
                entry.metadata.durationMs?.let { put("d", it) }
                put("s", entry.size)
                put("m", entry.modified)
            }
        }
        val data = NSPropertyListSerialization.dataWithPropertyList(root, NSPropertyListBinaryFormat_v1_0, 0u, null) ?: return
        data.writeToFile(file, atomically = true)
    }
}
