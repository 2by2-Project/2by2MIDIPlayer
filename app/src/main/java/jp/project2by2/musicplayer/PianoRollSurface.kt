package jp.project2by2.musicplayer

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.MidiChannelStatus
import dev.atsushieno.ktmidi.Midi1CompoundMessage
import dev.atsushieno.ktmidi.read
import java.util.PriorityQueue

private val MidiChannelNeonPalette = listOf(
    Color(0xFFFF5252),
    Color(0xFFFF6E40),
    Color(0xFFFFD740),
    Color(0xFFFFFF00),
    Color(0xFFEEFF41),
    Color(0xFFB2FF59),
    Color(0xFF69F0AE),
    Color(0xFF64FFDA),
    Color(0xFF18FFFF),
    Color(0xFFEEEEEE),
    Color(0xFF40C4FF),
    Color(0xFF448AFF),
    Color(0xFF536DFE),
    Color(0xFF7C4DFF),
    Color(0xFFB388FF),
    Color(0xFFFF4081)
)
private const val PIANO_ROLL_DEBUG_SAMPLE_LIMIT = 24

data class PianoRollNote(
    val noteNumber: Int,
    val startMs: Long,
    val endMs: Long,
    val startTick: Int,
    val endTick: Int,
    val velocity: Int,
    val channel: Int,
    val trackIndex: Int
)

data class TickTimeAnchor(
    val tick: Int,
    val ms: Long
)

data class PianoRollData(
    val notes: List<PianoRollNote>,
    val totalDurationMs: Long,
    val measurePositions: List<Long>,
    val measureTickPositions: List<Int>,
    val totalTicks: Int,
    val tickTimeAnchors: List<TickTimeAnchor>
)

suspend fun loadPianoRollData(context: Context, uri: Uri): PianoRollData {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes().asList() } ?: return PianoRollData(
        notes = emptyList(),
        totalDurationMs = 0L,
        measurePositions = emptyList(),
        measureTickPositions = emptyList(),
        totalTicks = 0,
        tickTimeAnchors = emptyList()
    )
    val music = Midi1Music().apply { read(bytes) }
    val maxTick = music.tracks.maxOfOrNull { t -> t.events.sumOf { it.deltaTime } } ?: 0
    val duration = music.getTimePositionInMillisecondsForTick(maxTick).toLong().coerceAtLeast(1L)
    val measures = calculateMeasurePositions(music, duration)
    val measureTicks = calculateMeasureTickPositions(music)
    val anchors = buildTickTimeAnchors(music, maxTick)
    val notes = extractPianoRollNotes(music)
    val tickSample = measureTicks.take(PIANO_ROLL_DEBUG_SAMPLE_LIMIT).joinToString(", ")
    val tickSpanSample = measureTicks.zipWithNext()
        .take(PIANO_ROLL_DEBUG_SAMPLE_LIMIT)
        .joinToString(", ") { (a, b) -> "${b - a}t" }
    Log.i(
        "PlaybackPianoRollTS",
        "loadPianoRollData: uri=$uri notes=${notes.size} maxTick=$maxTick measureTicks=${measureTicks.size} anchors=${anchors.size}"
    )
    Log.d("PlaybackPianoRollTS", "measureTicks(sample): $tickSample")
    Log.d("PlaybackPianoRollTS", "measureTickSpans(sample): $tickSpanSample")
    return PianoRollData(
        notes = notes,
        totalDurationMs = duration,
        measurePositions = measures,
        measureTickPositions = measureTicks,
        totalTicks = maxTick,
        tickTimeAnchors = anchors
    )
}

suspend fun loadPianoRollDataProgressive(
    context: Context,
    uri: Uri,
    chunkSize: Int = 400,
    onChunk: suspend (PianoRollData) -> Unit
): PianoRollData {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes().asList() } ?: return PianoRollData(
        notes = emptyList(),
        totalDurationMs = 0L,
        measurePositions = emptyList(),
        measureTickPositions = emptyList(),
        totalTicks = 0,
        tickTimeAnchors = emptyList()
    )
    val music = Midi1Music().apply { read(bytes) }
    val maxTick = music.tracks.maxOfOrNull { t -> t.events.sumOf { it.deltaTime } } ?: 0
    val duration = music.getTimePositionInMillisecondsForTick(maxTick).toLong().coerceAtLeast(1L)
    val measures = calculateMeasurePositions(music, duration)
    val measureTicks = calculateMeasureTickPositions(music)
    val anchors = buildTickTimeAnchors(music, maxTick)
    Log.i(
        "PlaybackPianoRollTS",
        "loadPianoRollDataProgressive: uri=$uri maxTick=$maxTick durationMs=$duration measureTicks=${measureTicks.size} anchors=${anchors.size}"
    )

    val notes = extractPianoRollNotesProgressive(music, chunkSize) { partial ->
        onChunk(
            PianoRollData(
                notes = partial,
                totalDurationMs = duration,
                measurePositions = measures,
                measureTickPositions = measureTicks,
                totalTicks = maxTick,
                tickTimeAnchors = anchors
            )
        )
    }
    return PianoRollData(
        notes = notes,
        totalDurationMs = duration,
        measurePositions = measures,
        measureTickPositions = measureTicks,
        totalTicks = maxTick,
        tickTimeAnchors = anchors
    )
}

private fun extractPianoRollNotes(music: Midi1Music): List<PianoRollNote> {
    val notes = mutableListOf<PianoRollNote>()
    val tickMsCache = HashMap<Int, Long>(4096)
    fun tickToMs(tick: Int): Long = tickMsCache.getOrPut(tick) { music.getTimePositionInMillisecondsForTick(tick).toLong() }

    for ((trackIndex, track) in music.tracks.withIndex()) {
        var tick = 0
        val activeNotes = mutableMapOf<Pair<Int, Int>, Triple<Int, Long, Int>>()
        for (event in track.events) {
            tick += event.deltaTime
            val msg = event.message
            val status = msg.statusByte.toInt() and 0xF0
            val channel = msg.statusByte.toInt() and 0x0F
            when (status) {
                MidiChannelStatus.NOTE_ON -> {
                    val note = msg.msb.toInt()
                    val velocity = msg.lsb.toInt()
                    if (velocity > 0) {
                        activeNotes[note to channel] = Triple(velocity, tickToMs(tick), tick)
                    } else {
                        activeNotes[note to channel]?.let { (vel, startMs, startTick) ->
                            val endMs = tickToMs(tick)
                            notes.add(PianoRollNote(note, startMs, endMs, startTick, tick, vel, channel, trackIndex))
                            activeNotes.remove(note to channel)
                        }
                    }
                }
                MidiChannelStatus.NOTE_OFF -> {
                    val note = msg.msb.toInt()
                    activeNotes[note to channel]?.let { (vel, startMs, startTick) ->
                        val endMs = tickToMs(tick)
                        notes.add(PianoRollNote(note, startMs, endMs, startTick, tick, vel, channel, trackIndex))
                        activeNotes.remove(note to channel)
                    }
                }
            }
        }
    }
    return notes
}

private suspend fun extractPianoRollNotesProgressive(
    music: Midi1Music,
    chunkSize: Int,
    onChunk: suspend (List<PianoRollNote>) -> Unit
): List<PianoRollNote> {
    data class TrackCursor(
        val trackIndex: Int,
        var eventIndex: Int,
        var absoluteTick: Int
    )

    val notes = mutableListOf<PianoRollNote>()
    val tickMsCache = HashMap<Int, Long>(4096)
    var lastPublished = 0
    var lastPublishAt = SystemClock.uptimeMillis()
    val activeByTrack = Array(music.tracks.size) { mutableMapOf<Pair<Int, Int>, Triple<Int, Long, Int>>() }
    val queue = PriorityQueue<TrackCursor> { a, b ->
        val byTick = a.absoluteTick.compareTo(b.absoluteTick)
        if (byTick != 0) byTick else a.trackIndex.compareTo(b.trackIndex)
    }

    fun tickToMs(tick: Int): Long = tickMsCache.getOrPut(tick) { music.getTimePositionInMillisecondsForTick(tick).toLong() }
    suspend fun publishIfNeeded(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (force || notes.size - lastPublished >= chunkSize || now - lastPublishAt >= 200L) {
            lastPublished = notes.size
            lastPublishAt = now
            onChunk(notes.toList())
        }
    }

    for ((trackIndex, track) in music.tracks.withIndex()) {
        if (track.events.isNotEmpty()) {
            queue.add(TrackCursor(trackIndex = trackIndex, eventIndex = 0, absoluteTick = track.events[0].deltaTime))
        }
    }

    while (queue.isNotEmpty()) {
        val cursor = queue.poll()
        val track = music.tracks[cursor.trackIndex]
        val event = track.events[cursor.eventIndex]
        val tick = cursor.absoluteTick
        val activeNotes = activeByTrack[cursor.trackIndex]

        val msg = event.message
        val status = msg.statusByte.toInt() and 0xF0
        val channel = msg.statusByte.toInt() and 0x0F
        when (status) {
            MidiChannelStatus.NOTE_ON -> {
                val note = msg.msb.toInt()
                val velocity = msg.lsb.toInt()
                if (velocity > 0) {
                    activeNotes[note to channel] = Triple(velocity, tickToMs(tick), tick)
                } else {
                    activeNotes[note to channel]?.let { (vel, startMs, startTick) ->
                        val endMs = tickToMs(tick)
                        notes.add(
                            PianoRollNote(
                                note,
                                startMs,
                                endMs,
                                startTick,
                                tick,
                                vel,
                                channel,
                                cursor.trackIndex
                            )
                        )
                        activeNotes.remove(note to channel)
                        publishIfNeeded()
                    }
                }
            }
            MidiChannelStatus.NOTE_OFF -> {
                val note = msg.msb.toInt()
                activeNotes[note to channel]?.let { (vel, startMs, startTick) ->
                    val endMs = tickToMs(tick)
                    notes.add(PianoRollNote(note, startMs, endMs, startTick, tick, vel, channel, cursor.trackIndex))
                    activeNotes.remove(note to channel)
                    publishIfNeeded()
                }
            }
        }

        val nextIndex = cursor.eventIndex + 1
        if (nextIndex < track.events.size) {
            cursor.eventIndex = nextIndex
            cursor.absoluteTick += track.events[nextIndex].deltaTime
            queue.add(cursor)
        }
    }

    publishIfNeeded(force = true)
    return notes
}

@Composable
fun PlaybackPianoRollView(
    notes: List<PianoRollNote>,
    measureTickPositions: List<Int>,
    tickTimeAnchors: List<TickTimeAnchor>,
    currentPositionMs: Long,
    loopPointMs: Long,
    endPointMs: Long,
    totalDurationMs: Long,
    totalTicks: Int,
    zoomLevel: Float = 10f,
    modifier: Modifier = Modifier
) {
    var previousNoteCount by remember { mutableIntStateOf(0) }
    var chunkStartIndex by remember { mutableIntStateOf(0) }
    val chunkReveal = remember { Animatable(1f) }

    LaunchedEffect(notes.size) {
        val newSize = notes.size
        if (newSize > previousNoteCount) {
            chunkStartIndex = previousNoteCount
            previousNoteCount = newSize
            chunkReveal.snapTo(0f)
            chunkReveal.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
            )
        } else {
            previousNoteCount = newSize
            chunkStartIndex = newSize
            chunkReveal.snapTo(1f)
        }
    }

    Box(modifier = modifier.background(Color(0xFF161616))) {
        Canvas(Modifier.fillMaxSize()) {
            val durationTicks = totalTicks.coerceAtLeast(1)
            val displayDurationTicks = durationTicks
            val viewport = (displayDurationTicks / zoomLevel).toInt().coerceAtLeast(1)
            val half = viewport / 2
            val currentTick = msToTick(currentPositionMs, tickTimeAnchors, durationTicks)
            val currentDisplayTick = currentTick
            val visibleStart = (currentDisplayTick - half).coerceIn(0, (displayDurationTicks - viewport).coerceAtLeast(0))
            val visibleEnd = visibleStart + viewport

            measureTickPositions.forEach { measureDisplayTick ->
                if (measureDisplayTick !in visibleStart..visibleEnd) return@forEach
                val x = ((measureDisplayTick - visibleStart).toFloat() / viewport.toFloat()) * size.width
                drawLine(
                    color = Color.Gray.copy(alpha = 0.45f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5f
                )
            }

            notes.forEachIndexed { index, note ->
                val startDisplayTick = note.startTick.coerceIn(0, durationTicks)
                val endDisplayTick = note.endTick.coerceIn(0, durationTicks)
                if (startDisplayTick >= visibleEnd || endDisplayTick <= visibleStart) return@forEachIndexed
                val x = ((startDisplayTick - visibleStart).toFloat() / viewport.toFloat()) * size.width
                val w = ((endDisplayTick - startDisplayTick).toFloat() / viewport.toFloat()) * size.width
                val y = ((127 - note.noteNumber).toFloat() / 127f) * size.height
                val h = size.height / 128f * 2f
                val channelColor = MidiChannelNeonPalette[note.channel.mod(MidiChannelNeonPalette.size)]
                val reveal = if (index >= chunkStartIndex) chunkReveal.value else 1f
                val animatedWidth = (w.coerceAtLeast(2f) * reveal).coerceAtLeast(2f)
                val animatedAlpha = 0.15f + (0.60f * reveal)
                drawRect(
                    color = channelColor.copy(alpha = animatedAlpha),
                    topLeft = Offset(x, y),
                    size = Size(animatedWidth, h)
                )
            }

            fun drawMarker(ms: Long, color: Color, width: Float) {
                val markerDisplayTick = msToTick(ms, tickTimeAnchors, durationTicks).coerceIn(0, durationTicks)
                if (markerDisplayTick !in visibleStart..visibleEnd) return
                val x = ((markerDisplayTick - visibleStart).toFloat() / viewport.toFloat()) * size.width
                drawLine(color = color, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = width)
            }

            drawMarker(endPointMs, Color.Red, 5f)
            drawMarker(loopPointMs, Color.Green, 5f)
            drawMarker(currentPositionMs, Color.White, 5f)
        }
    }
}

private fun calculateMeasureTickPositions(music: Midi1Music): List<Int> {
    val signatures = extractTimeSignatures(music)
    val ticksPerQuarterNote = music.deltaTimeSpec
    val measures = mutableListOf<Int>()
    var currentTick = 0
    val maxTick = music.tracks.maxOfOrNull { track ->
        track.events.sumOf { it.deltaTime }
    } ?: 0
    while (currentTick < maxTick) {
        measures.add(currentTick)
        val currentSig = signatures.lastOrNull { it.tick <= currentTick } ?: signatures.first()
        val ticksPerMeasure = (currentSig.numerator * 4 * ticksPerQuarterNote) / currentSig.denominator
        currentTick += ticksPerMeasure.coerceAtLeast(1)
    }
    if (measures.isNotEmpty()) {
        val sigSample = signatures
            .take(PIANO_ROLL_DEBUG_SAMPLE_LIMIT)
            .joinToString(", ") { "t${it.tick}:${it.numerator}/${it.denominator}" }
        val spans = measures.zipWithNext()
            .take(PIANO_ROLL_DEBUG_SAMPLE_LIMIT)
            .joinToString(", ") { (a, b) -> "${b - a}t" }
        Log.i(
            "PlaybackPianoRollTS",
            "calculateMeasureTickPositions: tpq=$ticksPerQuarterNote maxTick=$maxTick signatures=${signatures.size} measures=${measures.size}"
        )
        Log.d("PlaybackPianoRollTS", "signatures(sample): $sigSample")
        Log.d("PlaybackPianoRollTS", "measureSpansTick(sample): $spans")
    }
    return measures
}

private fun buildTickTimeAnchors(music: Midi1Music, maxTick: Int): List<TickTimeAnchor> {
    data class TempoPoint(val tick: Int, val usPerQuarter: Int)

    val rawTempo = mutableListOf<TempoPoint>()
    for (track in music.tracks) {
        var tick = 0
        for (event in track.events) {
            tick += event.deltaTime
            val msg = event.message
            if ((msg.statusByte.toInt() and 0xFF) == 0xFF && (msg.msb.toInt() and 0xFF) == 0x51) {
                val data = (msg as? Midi1CompoundMessage)?.extraData ?: continue
                if (data.size >= 3) {
                    val usPerQuarter = ((data[0].toInt() and 0xFF) shl 16) or
                        ((data[1].toInt() and 0xFF) shl 8) or
                        (data[2].toInt() and 0xFF)
                    rawTempo.add(TempoPoint(tick, usPerQuarter))
                }
            }
        }
    }

    val tempoEvents = rawTempo
        .sortedBy { it.tick }
        .fold(mutableListOf<TempoPoint>()) { acc, tp ->
            if (acc.isNotEmpty() && acc.last().tick == tp.tick) acc[acc.lastIndex] = tp else acc.add(tp)
            acc
        }

    val anchors = mutableListOf(TickTimeAnchor(0, 0L))
    val tpq = music.deltaTimeSpec.coerceAtLeast(1)
    var currentUsPerQuarter = 500_000
    var currentTick = 0
    var currentMs = 0L

    for (tempo in tempoEvents) {
        val tempoTick = tempo.tick.coerceIn(0, maxTick)
        if (tempoTick > currentTick) {
            val deltaTicks = (tempoTick - currentTick).toLong()
            currentMs += (deltaTicks * currentUsPerQuarter.toLong()) / (tpq.toLong() * 1000L)
            currentTick = tempoTick
            anchors.add(TickTimeAnchor(currentTick, currentMs))
        }
        currentUsPerQuarter = tempo.usPerQuarter
    }

    if (maxTick > currentTick) {
        val deltaTicks = (maxTick - currentTick).toLong()
        currentMs += (deltaTicks * currentUsPerQuarter.toLong()) / (tpq.toLong() * 1000L)
        anchors.add(TickTimeAnchor(maxTick, currentMs))
    } else if (anchors.last().tick != maxTick) {
        anchors.add(TickTimeAnchor(maxTick, anchors.last().ms))
    }

    return anchors
}

private fun msToTick(ms: Long, anchors: List<TickTimeAnchor>, totalTicks: Int): Int {
    if (anchors.isEmpty()) return 0
    val clampedMs = ms.coerceAtLeast(0L)
    if (clampedMs <= anchors.first().ms) return anchors.first().tick
    if (clampedMs >= anchors.last().ms) return anchors.last().tick

    var low = 0
    var high = anchors.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val value = anchors[mid].ms
        when {
            value < clampedMs -> low = mid + 1
            value > clampedMs -> high = mid - 1
            else -> return anchors[mid].tick.coerceIn(0, totalTicks)
        }
    }

    val right = low.coerceIn(1, anchors.lastIndex)
    val left = right - 1
    val a = anchors[left]
    val b = anchors[right]
    val spanMs = (b.ms - a.ms).coerceAtLeast(1L)
    val ratio = (clampedMs - a.ms).toDouble() / spanMs.toDouble()
    val tick = a.tick + ((b.tick - a.tick) * ratio).toInt()
    return tick.coerceIn(0, totalTicks)
}
