package jp.project2by2.musicplayer

import android.content.Context
import android.net.Uri
import android.util.Log

data class LoopEditorInitialData(
    val pianoRollData: PianoRollData,
    val loopPointMs: Long
)

private object PianoRollIndexCache {
    @Volatile
    var uriKey: String? = null
    @Volatile
    var index: PianoRollIndex? = null
}

private fun readMidiBytes(context: Context, uri: Uri): ByteArray? {
    return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
}

private fun getOrBuildPianoRollIndex(context: Context, uri: Uri): PianoRollIndex? {
    val key = uri.toString()
    PianoRollIndexCache.index?.let { cached ->
        if (PianoRollIndexCache.uriKey == key) return cached
    }
    val bytes = readMidiBytes(context, uri) ?: return null
    val index = parseSmfToPianoRollIndex(bytes) ?: return null
    PianoRollIndexCache.uriKey = key
    PianoRollIndexCache.index = index
    return index
}

private fun lowerBoundStartTickFast(notes: List<PianoRollNote>, tick: Int): Int {
    var low = 0
    var high = notes.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (notes[mid].startTick < tick) low = mid + 1 else high = mid
    }
    return low
}

suspend fun loadPianoRollMetadataFast(context: Context, uri: Uri): PianoRollData {
    val index = getOrBuildPianoRollIndex(context, uri) ?: return PianoRollData(
        notes = emptyList(),
        totalDurationMs = 0L,
        measurePositions = emptyList(),
        measureTickPositions = emptyList(),
        totalTicks = 0,
        tickTimeAnchors = emptyList()
    )
    return PianoRollData(
        notes = emptyList(),
        totalDurationMs = index.totalDurationMs,
        measurePositions = index.measurePositionsMs,
        measureTickPositions = index.measureTickPositions,
        totalTicks = index.totalTicks,
        tickTimeAnchors = index.tickTimeAnchors
    )
}

suspend fun loadPianoRollMeasureChunkFast(
    context: Context,
    uri: Uri,
    startMeasureIndex: Int,
    measureCount: Int
): List<PianoRollNote> {
    val index = getOrBuildPianoRollIndex(context, uri) ?: return emptyList()
    val measureTicks = index.measureTickPositions
    if (measureTicks.isEmpty() || measureCount <= 0) return emptyList()
    val startMeasure = startMeasureIndex.coerceIn(0, measureTicks.lastIndex)
    val endMeasureExclusive = (startMeasure + measureCount).coerceAtMost(measureTicks.size)
    val startTick = measureTicks[startMeasure]
    val endTick = if (endMeasureExclusive < measureTicks.size) {
        measureTicks[endMeasureExclusive]
    } else {
        index.totalTicks
    }.coerceAtLeast(startTick + 1)

    val notes = index.notes
    val startIdx = (lowerBoundStartTickFast(notes, startTick) - 128).coerceAtLeast(0)
    val out = ArrayList<PianoRollNote>(2048)
    var i = startIdx
    while (i < notes.size) {
        val n = notes[i]
        if (n.startTick > endTick) break
        if (n.endTick > startTick && n.startTick < endTick) out.add(n)
        i++
    }
    return out
}

suspend fun loadPianoRollDataFast(context: Context, uri: Uri): PianoRollData {
    val index = getOrBuildPianoRollIndex(context, uri) ?: return PianoRollData(
        notes = emptyList(),
        totalDurationMs = 0L,
        measurePositions = emptyList(),
        measureTickPositions = emptyList(),
        totalTicks = 0,
        tickTimeAnchors = emptyList()
    )
    Log.i(
        "PlaybackPianoRollTS",
        "loadPianoRollDataFast: uri=$uri notes=${index.notes.size} totalTicks=${index.totalTicks} measureTicks=${index.measureTickPositions.size}"
    )
    return PianoRollData(
        notes = index.notes,
        totalDurationMs = index.totalDurationMs,
        measurePositions = index.measurePositionsMs,
        measureTickPositions = index.measureTickPositions,
        totalTicks = index.totalTicks,
        tickTimeAnchors = index.tickTimeAnchors
    )
}

suspend fun loadLoopEditorInitialDataFast(context: Context, uri: Uri): LoopEditorInitialData? {
    val index = getOrBuildPianoRollIndex(context, uri) ?: return null
    val pianoRollData = PianoRollData(
        notes = index.notes,
        totalDurationMs = index.totalDurationMs,
        measurePositions = index.measurePositionsMs,
        measureTickPositions = index.measureTickPositions,
        totalTicks = index.totalTicks,
        tickTimeAnchors = index.tickTimeAnchors
    )
    val loopPointMs = index.loopPointTick
        ?.let { tickToMsFast(it, index.tickTimeAnchors) }
        ?.coerceIn(0L, (index.totalDurationMs - 1L).coerceAtLeast(0L))
        ?: 0L
    return LoopEditorInitialData(
        pianoRollData = pianoRollData,
        loopPointMs = loopPointMs
    )
}
