package jp.project2by2.musicplayer

import dev.atsushieno.ktmidi.Midi1CompoundMessage
import dev.atsushieno.ktmidi.Midi1Event
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.Midi1SimpleMessage
import dev.atsushieno.ktmidi.Midi1Track
import dev.atsushieno.ktmidi.MidiChannelStatus
import dev.atsushieno.ktmidi.read
import kotlin.math.abs

// The loop point editor's MIDI rules (Android's EditLoopPointActivity), shared with iOS.

/** What the loop point editor opens with: the piano roll and the current CC#111 position. */
data class LoopEditorInitialData(
    val pianoRollData: PianoRollData,
    val loopPointMs: Long
)

fun loopEditorInitialData(index: PianoRollIndex): LoopEditorInitialData {
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
    return LoopEditorInitialData(pianoRollData = pianoRollData, loopPointMs = loopPointMs)
}

fun snapToMeasureLine(targetMs: Long, pianoRollData: PianoRollData): Long {
    val nearestTick = resolveSavedTick(targetMs, pianoRollData)
    return tickToMsFast(nearestTick, pianoRollData.tickTimeAnchors)
}

private fun buildQuarterMeasureSnapTicks(pianoRollData: PianoRollData): List<Int> {
    val measureTicks = pianoRollData.measureTickPositions
    if (measureTicks.isEmpty()) return emptyList()
    val snapTicks = linkedSetOf<Int>()
    for ((index, measureStartTick) in measureTicks.withIndex()) {
        val measureEndTick = if (index + 1 < measureTicks.size) {
            measureTicks[index + 1]
        } else {
            pianoRollData.totalTicks.coerceAtLeast(measureStartTick + 1)
        }
        val measureLength = (measureEndTick - measureStartTick).coerceAtLeast(1)
        for (step in 0..3) {
            val tick = measureStartTick + ((measureLength * step) / 4.0).toInt()
            snapTicks.add(tick.coerceIn(0, pianoRollData.totalTicks))
        }
    }
    snapTicks.add(pianoRollData.totalTicks)
    return snapTicks.toList()
}

/** The tick saved for [targetMs]: the nearest quarter of a measure. */
fun resolveSavedTick(targetMs: Long, pianoRollData: PianoRollData): Int {
    if (targetMs >= pianoRollData.totalDurationMs) {
        return pianoRollData.totalTicks
    }
    val snapTicks = buildQuarterMeasureSnapTicks(pianoRollData)
    if (snapTicks.isEmpty()) return msToTick(targetMs, pianoRollData.tickTimeAnchors, pianoRollData.totalTicks)
    val targetTick = msToTick(targetMs, pianoRollData.tickTimeAnchors, pianoRollData.totalTicks)
    return snapTicks.minByOrNull { abs(it - targetTick) } ?: targetTick
}

fun approximateTickFromMs(targetMs: Long, music: Midi1Music): Int {
    val maxTick = music.tracks.maxOfOrNull { track -> track.events.sumOf { it.deltaTime } } ?: 0
    var low = 0
    var high = maxTick
    while (low < high) {
        val mid = (low + high) / 2
        val midMs = music.getTimePositionInMillisecondsForTick(mid).toLong()
        if (midMs < targetMs) low = mid + 1 else high = mid
    }
    return low
}

fun findLoopPointFromMusic(music: Midi1Music): Long? {
    val tickMsCache = HashMap<Int, Long>(512)
    for (track in music.tracks) {
        var tick = 0
        for (event in track.events) {
            tick += event.deltaTime
            val message = event.message
            val isCc = ((message.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC)
            if (isCc && message.msb.toInt() == 111) {
                return tickMsCache.getOrPut(tick) {
                    music.getTimePositionInMillisecondsForTick(tick).toLong()
                }
            }
        }
    }
    return null
}

/**
 * A copy of [bytes] with one CC#111 at [loopTick] (track 1) and every track cut at [endTick].
 * Null when the file cannot be read or written.
 */
fun buildLoopPointMidi(bytes: ByteArray, loopTick: Int, endTick: Int): ByteArray? = try {
    val music = Midi1Music().apply { read(bytes.toList()) }
    val maxTick = music.tracks.maxOfOrNull { track -> track.events.sumOf { it.deltaTime } } ?: 0
    val newLoopTick = loopTick.coerceIn(0, maxTick)
    val newEndTick = endTick.coerceIn((newLoopTick + 1).coerceAtMost(maxTick), maxTick)

    // Remove the previous loop starts. Their delta times move to the next event, so the rest of the
    // track keeps its timing.
    for (track in music.tracks) {
        val kept = mutableListOf<Midi1Event>()
        var carriedTicks = 0
        for (event in track.events) {
            val message = event.message
            if (((message.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC) && message.msb.toInt() == 111) {
                carriedTicks += event.deltaTime
                continue
            }
            kept += if (carriedTicks == 0) event else Midi1Event(event.deltaTime + carriedTicks, message)
            carriedTicks = 0
        }
        track.events.clear()
        track.events.addAll(kept)
    }

    for (track in music.tracks) {
        var accumulatedTick = 0
        val keptEvents = mutableListOf<Midi1Event>()
        val trailingMetaOrSysEx = mutableListOf<Midi1Event>()
        for (event in track.events) {
            val message = event.message
            val status = message.statusByte.toInt() and 0xFF
            val msb = message.msb.toInt() and 0xFF
            val isMetaEvent = status == 0xFF && msb != 0x2F
            val isSysEx = status == 0xF0

            accumulatedTick += event.deltaTime
            if (accumulatedTick <= newEndTick) {
                keptEvents.add(event)
            } else if (isMetaEvent || isSysEx) {
                trailingMetaOrSysEx.add(Midi1Event(0, message))
            } else {
                break
            }
        }

        keptEvents.addAll(trailingMetaOrSysEx)
        if (keptEvents.isEmpty() || !isEndOfTrack(keptEvents.last())) {
            val lastTick = keptEvents.sumOf { it.deltaTime }
            val endDelta = (newEndTick - lastTick).coerceAtLeast(0)
            keptEvents.add(createEndOfTrackEvent(endDelta))
        }

        track.events.clear()
        track.events.addAll(keptEvents)
    }

    if (music.tracks.isNotEmpty()) {
        insertCCEvent(music.tracks[0], newLoopTick, 111, 0)
    }

    runCatching { writeMidiToBytes(music) }.getOrNull()
} catch (e: Exception) {
    null
}

fun isEndOfTrack(event: Midi1Event): Boolean {
    val message = event.message
    return (message.statusByte.toInt() and 0xFF) == 0xFF && (message.msb.toInt() and 0xFF) == 0x2F
}

fun createEndOfTrackEvent(deltaTime: Int): Midi1Event {
    return Midi1Event(deltaTime, Midi1CompoundMessage(0xFF, 0x2F, 0, byteArrayOf()))
}

fun insertCCEvent(track: Midi1Track, targetTick: Int, ccNumber: Int, value: Int) {
    var accumulatedTick = 0
    var insertIndex = 0
    for ((index, event) in track.events.withIndex()) {
        // Never after End of Track, where players stop reading the track.
        if (accumulatedTick + event.deltaTime > targetTick || isEndOfTrack(event)) {
            insertIndex = index
            break
        }
        accumulatedTick += event.deltaTime
        insertIndex = index + 1
    }

    val deltaTime = targetTick - accumulatedTick
    val ccEvent = Midi1Event(
        deltaTime,
        Midi1SimpleMessage(MidiChannelStatus.CC or 0, ccNumber, value)
    )
    track.events.add(insertIndex, ccEvent)

    if (insertIndex + 1 < track.events.size) {
        val next = track.events[insertIndex + 1]
        val adjustedDelta = (next.deltaTime - deltaTime).coerceAtLeast(0)
        track.events[insertIndex + 1] = Midi1Event(adjustedDelta, next.message)
    }
}

fun writeMidiToBytes(music: Midi1Music): ByteArray {
    val output = mutableListOf<Byte>()
    output.addAll("MThd".encodeToByteArray().toList())
    output.addAll(writeInt32(6))
    output.addAll(writeInt16(music.format.toInt()))
    output.addAll(writeInt16(music.tracks.size))
    output.addAll(writeInt16(music.deltaTimeSpec))

    for (track in music.tracks) {
        val trackBytes = mutableListOf<Byte>()
        for (event in track.events) {
            trackBytes.addAll(writeVariableLengthQuantity(event.deltaTime))
            val message = event.message
            trackBytes.add(message.statusByte)

            val status = message.statusByte.toInt() and 0xFF
            val statusHigh = status and 0xF0
            when {
                status == 0xFF -> {
                    trackBytes.add(message.msb)
                    val extraData = (message as? Midi1CompoundMessage)?.extraData ?: byteArrayOf()
                    trackBytes.addAll(writeVariableLengthQuantity(extraData.size))
                    trackBytes.addAll(extraData.toList())
                }
                status == 0xF0 -> {
                    val extraData = (message as? Midi1CompoundMessage)?.extraData ?: byteArrayOf()
                    trackBytes.addAll(writeVariableLengthQuantity(extraData.size))
                    trackBytes.addAll(extraData.toList())
                }
                statusHigh == 0xC0 || statusHigh == 0xD0 -> {
                    trackBytes.add(message.msb)
                }
                else -> {
                    trackBytes.add(message.msb)
                    trackBytes.add(message.lsb)
                }
            }
        }

        output.addAll("MTrk".encodeToByteArray().toList())
        output.addAll(writeInt32(trackBytes.size))
        output.addAll(trackBytes)
    }

    return output.toByteArray()
}

private fun writeInt32(value: Int): List<Byte> {
    return listOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

private fun writeInt16(value: Int): List<Byte> {
    return listOf(
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

private fun writeVariableLengthQuantity(value: Int): List<Byte> {
    var working = value
    var buffer = working and 0x7F
    val bytes = mutableListOf<Byte>()
    while (true) {
        working = working shr 7
        if (working <= 0) break
        buffer = (buffer shl 8) or ((working and 0x7F) or 0x80)
    }
    while (true) {
        bytes.add((buffer and 0xFF).toByte())
        if ((buffer and 0x80) != 0) {
            buffer = buffer shr 8
        } else {
            break
        }
    }
    return bytes
}
