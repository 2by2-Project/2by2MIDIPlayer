package jp.project2by2.musicplayer.desktop

import jp.project2by2.musicplayer.PianoRollNote

/** Skip notes outside the viewport while retaining sustained notes that began earlier. */
internal class NoteViewport(private val notes: List<PianoRollNote>) {
    private val maximumEnd = LongArray(notes.size).also { ends ->
        var maximum = Long.MIN_VALUE
        notes.forEachIndexed { i, note -> maximum = maxOf(maximum, note.endMs); ends[i] = maximum }
    }
    fun visible(start: Long, end: Long): List<PianoRollNote> {
        var low = 0
        var high = notes.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (maximumEnd[mid] < start) low = mid + 1 else high = mid
        }
        val first = low
        high = notes.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (notes[mid].startMs <= end) low = mid + 1 else high = mid
        }
        return notes.subList(first, low).filter { it.endMs >= start }
    }
}
