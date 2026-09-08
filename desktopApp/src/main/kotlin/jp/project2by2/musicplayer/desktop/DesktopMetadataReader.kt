package jp.project2by2.musicplayer.desktop

import jp.project2by2.musicplayer.MidiMetadata
import jp.project2by2.musicplayer.parseMidiMetadata
import java.io.File

/** Only access/caching is platform-specific. Different folders with equal filenames never collide. */
class DesktopMetadataReader {
    private data class Signature(val length: Long, val modified: Long)
    private data class Entry(val signature: Signature, val metadata: MidiMetadata)
    private val cache = LinkedHashMap<String, Entry>()

    @Synchronized
    fun read(file: File): MidiMetadata {
        require(file.isFile) { "MIDIファイルが見つかりません: $file" }
        require(file.length() <= 64L * 1024 * 1024) { "64MB以下のMIDIファイルを選択してください" }
        val key = file.canonicalPath
        val signature = Signature(file.length(), file.lastModified())
        cache[key]?.takeIf { it.signature == signature }?.let { return it.metadata }
        val metadata = parseMidiMetadata(file.readBytes())
        cache[key] = Entry(signature, metadata)
        if (cache.size > 512) cache.remove(cache.keys.first())
        return metadata
    }
}
