package jp.project2by2.musicplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlin.math.max

private data class RawNote(
    val noteNumber: Int,
    val startTick: Int,
    val endTick: Int,
    val velocity: Int,
    val channel: Int,
    val trackIndex: Int
)

private data class TempoEvent(val tick: Int, val usPerQuarter: Int)
private data class TimeSigEvent(val tick: Int, val numerator: Int, val denominator: Int)

private data class PianoRollIndex(
    val totalTicks: Int,
    val totalDurationMs: Long,
    val tickTimeAnchors: List<TickTimeAnchor>,
    val measureTickPositions: List<Int>,
    val measurePositionsMs: List<Long>,
    val notes: List<PianoRollNote>
)
private const val FAST_ROLL_DEBUG_SAMPLE_LIMIT = 24

private object PianoRollIndexCache {
    @Volatile
    var uriKey: String? = null
    @Volatile
    var index: PianoRollIndex? = null
}

private class SmfReader(private val bytes: ByteArray) {
    var pos: Int = 0

    fun canRead(n: Int): Boolean = pos + n <= bytes.size
    fun readU8(): Int = bytes[pos++].toInt() and 0xFF
    fun readU16(): Int = (readU8() shl 8) or readU8()
    fun readU32(): Int = (readU8() shl 24) or (readU8() shl 16) or (readU8() shl 8) or readU8()
    fun readAscii4(): String = String(bytes, pos, 4).also { pos += 4 }
    fun skip(n: Int) { pos = (pos + n).coerceAtMost(bytes.size) }

    fun readVarLen(): Int {
        var value = 0
        repeat(4) {
            val b = readU8()
            value = (value shl 7) or (b and 0x7F)
            if ((b and 0x80) == 0) return value
        }
        return value
    }
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

private fun parseSmfToPianoRollIndex(bytes: ByteArray): PianoRollIndex? {
    val r = SmfReader(bytes)
    if (!r.canRead(14)) return null
    if (r.readAscii4() != "MThd") return null
    val headerLen = r.readU32()
    if (headerLen < 6 || !r.canRead(headerLen)) return null
    r.readU16() // format
    val trackCount = r.readU16()
    val division = r.readU16()
    if (headerLen > 6) r.skip(headerLen - 6)
    if (division <= 0 || trackCount <= 0) return null
    val ticksPerQuarter = division

    val rawNotes = ArrayList<RawNote>(8192)
    val tempoEvents = ArrayList<TempoEvent>(64)
    val timeSigEvents = ArrayList<TimeSigEvent>(32)
    var maxTick = 0

    repeat(trackCount) { trackIndex ->
        if (!r.canRead(8)) return@repeat
        val chunkType = r.readAscii4()
        val len = r.readU32()
        if (chunkType != "MTrk" || !r.canRead(len)) {
            r.skip(len)
            return@repeat
        }
        val trackEnd = r.pos + len
        var tick = 0
        var runningStatus = -1
        val active = HashMap<Int, Pair<Int, Int>>() // key(note+ch) -> (startTick, velocity)

        while (r.pos < trackEnd && r.canRead(1)) {
            val delta = r.readVarLen()
            tick += delta
            if (tick > maxTick) maxTick = tick
            if (!r.canRead(1)) break
            var status = r.readU8()
            var firstData: Int? = null
            if (status < 0x80) {
                if (runningStatus < 0x80) break
                firstData = status
                status = runningStatus
            } else if (status < 0xF0) {
                runningStatus = status
            }

            when {
                status == 0xFF -> {
                    if (!r.canRead(1)) break
                    val metaType = r.readU8()
                    val metaLen = r.readVarLen()
                    if (!r.canRead(metaLen)) break
                    if (metaType == 0x51 && metaLen >= 3) {
                        val b0 = r.readU8()
                        val b1 = r.readU8()
                        val b2 = r.readU8()
                        val usq = (b0 shl 16) or (b1 shl 8) or b2
                        tempoEvents.add(TempoEvent(tick, usq))
                        if (metaLen > 3) r.skip(metaLen - 3)
                    } else if (metaType == 0x58 && metaLen >= 2) {
                        val num = r.readU8()
                        val denomPow = r.readU8()
                        val den = 1 shl denomPow
                        timeSigEvents.add(TimeSigEvent(tick, num.coerceAtLeast(1), den.coerceAtLeast(1)))
                        if (metaLen > 2) r.skip(metaLen - 2)
                    } else {
                        r.skip(metaLen)
                    }
                }
                status == 0xF0 || status == 0xF7 -> {
                    val syxLen = r.readVarLen()
                    if (!r.canRead(syxLen)) break
                    r.skip(syxLen)
                }
                status in 0x80..0xEF -> {
                    val eventType = status and 0xF0
                    val channel = status and 0x0F
                    val needTwo = eventType != 0xC0 && eventType != 0xD0
                    val d1 = if (firstData != null) {
                        firstData
                    } else {
                        if (!r.canRead(1)) break
                        r.readU8()
                    }
                    val d2 = if (needTwo) {
                        if (!r.canRead(1)) break
                        r.readU8()
                    } else 0

                    if (eventType == 0x90 || eventType == 0x80) {
                        val note = d1 and 0x7F
                        val velocity = d2 and 0x7F
                        val key = (channel shl 8) or note
                        if (eventType == 0x90 && velocity > 0) {
                            active[key] = tick to velocity
                        } else {
                            val start = active.remove(key)
                            if (start != null && tick > start.first) {
                                rawNotes.add(
                                    RawNote(
                                        noteNumber = note,
                                        startTick = start.first,
                                        endTick = tick,
                                        velocity = start.second,
                                        channel = channel,
                                        trackIndex = trackIndex
                                    )
                                )
                            }
                        }
                    }
                }
                else -> {
                    runningStatus = -1
                }
            }
        }
        if (r.pos < trackEnd) r.pos = trackEnd
    }

    val anchors = buildAnchorsFromTempo(maxTick, ticksPerQuarter, tempoEvents)
    val tickToMs = { tick: Int -> tickToMsFast(tick, anchors) }
    val measureTicks = buildMeasureTicks(maxTick, ticksPerQuarter, timeSigEvents)
    val measureMs = measureTicks.map(tickToMs)
    val notes = rawNotes
        .asSequence()
        .map { n ->
            PianoRollNote(
                noteNumber = n.noteNumber,
                startMs = tickToMs(n.startTick),
                endMs = tickToMs(n.endTick),
                startTick = n.startTick,
                endTick = n.endTick,
                velocity = n.velocity,
                channel = n.channel,
                trackIndex = n.trackIndex
            )
        }
        .sortedBy { it.startTick }
        .toList()
    val totalDuration = if (maxTick <= 0) 0L else tickToMs(maxTick)
    val timeSigSample = timeSigEvents
        .sortedBy { it.tick }
        .take(FAST_ROLL_DEBUG_SAMPLE_LIMIT)
        .joinToString(", ") { "t${it.tick}:${it.numerator}/${it.denominator}" }
    val measureTickSample = measureTicks
        .take(FAST_ROLL_DEBUG_SAMPLE_LIMIT)
        .joinToString(", ")
    val measureSpanSample = measureTicks
        .zipWithNext()
        .take(FAST_ROLL_DEBUG_SAMPLE_LIMIT)
        .joinToString(", ") { (a, b) -> "${b - a}t" }
    Log.i(
        "PlaybackPianoRollTS",
        "parseSmfToPianoRollIndex: tpq=$ticksPerQuarter tracks=$trackCount maxTick=$maxTick timeSigEvents=${timeSigEvents.size} measureCount=${measureTicks.size} notes=${notes.size}"
    )
    Log.d("PlaybackPianoRollTS", "timeSigEvents(sample): $timeSigSample")
    Log.d("PlaybackPianoRollTS", "measureTicks(sample): $measureTickSample")
    Log.d("PlaybackPianoRollTS", "measureTickSpans(sample): $measureSpanSample")

    return PianoRollIndex(
        totalTicks = maxTick,
        totalDurationMs = totalDuration.coerceAtLeast(1L),
        tickTimeAnchors = anchors,
        measureTickPositions = measureTicks,
        measurePositionsMs = measureMs,
        notes = notes
    )
}

private fun buildAnchorsFromTempo(
    maxTick: Int,
    ticksPerQuarter: Int,
    tempoEvents: List<TempoEvent>
): List<TickTimeAnchor> {
    val sorted = tempoEvents
        .sortedBy { it.tick }
        .fold(ArrayList<TempoEvent>()) { acc, e ->
            if (acc.isNotEmpty() && acc.last().tick == e.tick) {
                acc[acc.lastIndex] = e
            } else {
                acc.add(e)
            }
            acc
        }
    val anchors = ArrayList<TickTimeAnchor>(sorted.size + 2)
    anchors.add(TickTimeAnchor(0, 0L))
    var currentTempo = 500_000 // us/qn
    var currentTick = 0
    var currentMs = 0L
    val tpq = max(1, ticksPerQuarter).toLong()
    for (e in sorted) {
        val t = e.tick.coerceIn(0, maxTick)
        if (t > currentTick) {
            val dt = (t - currentTick).toLong()
            currentMs += (dt * currentTempo.toLong()) / (tpq * 1000L)
            currentTick = t
            anchors.add(TickTimeAnchor(currentTick, currentMs))
        }
        currentTempo = e.usPerQuarter
    }
    if (maxTick > currentTick) {
        val dt = (maxTick - currentTick).toLong()
        currentMs += (dt * currentTempo.toLong()) / (tpq * 1000L)
        anchors.add(TickTimeAnchor(maxTick, currentMs))
    } else if (anchors.last().tick != maxTick) {
        anchors.add(TickTimeAnchor(maxTick, anchors.last().ms))
    }
    return anchors
}

private fun buildMeasureTicks(
    maxTick: Int,
    ticksPerQuarter: Int,
    timeSigEvents: List<TimeSigEvent>
): List<Int> {
    val base = if (timeSigEvents.isEmpty()) {
        listOf(TimeSigEvent(0, 4, 4))
    } else {
        timeSigEvents.sortedBy { it.tick }
    }
    val sigs = ArrayList<TimeSigEvent>(base.size + 1)
    if (base.first().tick > 0) sigs.add(TimeSigEvent(0, 4, 4))
    for (e in base) {
        if (sigs.isNotEmpty() && sigs.last().tick == e.tick) {
            sigs[sigs.lastIndex] = e
        } else {
            sigs.add(e)
        }
    }

    val result = ArrayList<Int>(1024)
    var tick = 0
    var sigIndex = 0
    while (tick < maxTick) {
        result.add(tick)
        while (sigIndex + 1 < sigs.size && sigs[sigIndex + 1].tick <= tick) {
            sigIndex++
        }
        val sig = sigs[sigIndex]
        val ticksPerMeasure = ((sig.numerator * 4 * ticksPerQuarter) / max(1, sig.denominator)).coerceAtLeast(1)
        val nextSigTick = if (sigIndex + 1 < sigs.size) sigs[sigIndex + 1].tick else Int.MAX_VALUE
        val nextMeasureTick = tick + ticksPerMeasure
        tick = when {
            nextMeasureTick <= tick -> tick + 1
            nextMeasureTick <= nextSigTick -> nextMeasureTick
            nextSigTick > tick -> nextSigTick
            else -> nextMeasureTick
        }
    }
    return result
}

private fun tickToMsFast(tick: Int, anchors: List<TickTimeAnchor>): Long {
    if (anchors.isEmpty()) return 0L
    if (tick <= anchors.first().tick) return anchors.first().ms
    if (tick >= anchors.last().tick) return anchors.last().ms
    var low = 0
    var high = anchors.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val v = anchors[mid].tick
        when {
            v < tick -> low = mid + 1
            v > tick -> high = mid - 1
            else -> return anchors[mid].ms
        }
    }
    val right = low.coerceIn(1, anchors.lastIndex)
    val left = right - 1
    val a = anchors[left]
    val b = anchors[right]
    val dt = (b.tick - a.tick).coerceAtLeast(1)
    val ratio = (tick - a.tick).toDouble() / dt.toDouble()
    return a.ms + ((b.ms - a.ms) * ratio).toLong()
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
