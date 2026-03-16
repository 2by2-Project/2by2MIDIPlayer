package jp.project2by2.musicplayer

import android.os.Handler
import android.os.SystemClock
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSMIDI

internal class EventDrivenMidiEngine(
    private val streamHandle: Int,
    private val handler: Handler,
    private val parsedMidi: ParsedMidiTimeline,
    private val reapplyStreamSettings: () -> Unit,
    private val onSongBoundary: () -> Unit
) {
    private val channelStates = Array(16) { ChannelSnapshot() }
    private val activeNotes = Array(16) { IntArray(128) }
    private val pumpRunnable = Runnable { pump() }

    private var nextEventIndex = 0
    private var basePositionMs = 0L
    private var baseUptimeMs = 0L
    private var playing = false
    private var boundaryPending = false

    fun play() {
        if (playing) return
        playing = true
        baseUptimeMs = SystemClock.uptimeMillis()
        BASS.BASS_ChannelPlay(streamHandle, false)
        schedulePump()
    }

    fun pause() {
        if (!playing) return
        basePositionMs = getPositionMs()
        playing = false
        handler.removeCallbacks(pumpRunnable)
        BASS.BASS_ChannelPause(streamHandle)
    }

    fun stop() {
        pause()
        seekTo(0L)
    }

    fun release() {
        playing = false
        handler.removeCallbacks(pumpRunnable)
    }

    fun isPlaying(): Boolean = playing

    fun getPositionMs(): Long {
        if (!playing) return basePositionMs
        val elapsed = (SystemClock.uptimeMillis() - baseUptimeMs).coerceAtLeast(0L)
        return (basePositionMs + elapsed).coerceAtMost(parsedMidi.durationMs)
    }

    fun getDurationMs(): Long = parsedMidi.durationMs

    fun getCurrentTick(): Int {
        val index = parsedMidi.findLastEventIndexAtOrBefore(getPositionMs())
        return if (index >= 0) parsedMidi.eventTicks[index] else 0
    }

    fun seekTo(positionMs: Long) {
        val wasPlaying = playing
        val targetMs = positionMs.coerceIn(0L, parsedMidi.durationMs)
        playing = false
        handler.removeCallbacks(pumpRunnable)
        basePositionMs = targetMs
        baseUptimeMs = SystemClock.uptimeMillis()
        boundaryPending = false

        BASS.BASS_ChannelSetAttribute(streamHandle, BASS.BASS_ATTRIB_VOL, 0f)
        resetRealtimeStream()
        restoreAt(targetMs)
        BASS.BASS_ChannelSetAttribute(streamHandle, BASS.BASS_ATTRIB_VOL, 1f)

        if (wasPlaying) {
            playing = true
            BASS.BASS_ChannelPlay(streamHandle, false)
            schedulePump()
        }
    }

    private fun resetRealtimeStream() {
        BASS.BASS_ChannelPause(streamHandle)
        BASS.BASS_ChannelSetPosition(streamHandle, 0, BASS.BASS_POS_BYTE)
        for (channel in 0 until 16) {
            BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_NOTESOFF, 0)
            BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_SOUNDOFF, 0)
        }
        reapplyStreamSettings()
        for (channel in 0 until 16) {
            channelStates[channel].clear()
            activeNotes[channel].fill(0)
        }
    }

    private fun restoreAt(targetMs: Long) {
        val targetIndex = parsedMidi.findLastEventIndexAtOrBefore(targetMs)
        clearPlaybackState()
        nextEventIndex = 0
        for (index in 0..targetIndex) {
            applyEventState(index)
            nextEventIndex = index + 1
        }
        applyCurrentStateToStream()
    }

    private fun pump() {
        if (!playing) return
        val nowPosition = getPositionMs()
        while (nextEventIndex < parsedMidi.eventCount && parsedMidi.eventTimesMs[nextEventIndex] <= nowPosition) {
            dispatchEvent(nextEventIndex)
            nextEventIndex += 1
        }
        if (nextEventIndex >= parsedMidi.eventCount && nowPosition >= parsedMidi.durationMs) {
            if (!boundaryPending) {
                boundaryPending = true
                basePositionMs = parsedMidi.durationMs
                baseUptimeMs = SystemClock.uptimeMillis()
                playing = false
                BASS.BASS_ChannelPause(streamHandle)
                onSongBoundary()
            }
            return
        }
        schedulePump()
    }

    private fun schedulePump() {
        if (!playing) return
        handler.removeCallbacks(pumpRunnable)
        val nowPosition = getPositionMs()
        val delayMs = if (nextEventIndex < parsedMidi.eventCount) {
            (parsedMidi.eventTimesMs[nextEventIndex] - nowPosition).coerceIn(1L, 5L)
        } else {
            (parsedMidi.durationMs - nowPosition).coerceIn(1L, 5L)
        }
        handler.postDelayed(pumpRunnable, delayMs)
    }

    private fun dispatchEvent(index: Int) {
        val packed = parsedMidi.eventData[index]
        val type = packed ushr EVENT_TYPE_SHIFT
        val channel = (packed ushr EVENT_CHANNEL_SHIFT) and EVENT_CHANNEL_MASK
        val a = (packed ushr EVENT_A_SHIFT) and EVENT_A_MASK
        val b = packed and EVENT_B_MASK
        when (type) {
            EVENT_NOTE_ON -> {
                activeNotes[channel][a] = b
                BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_NOTE, a or (b shl 8))
            }
            EVENT_NOTE_OFF -> {
                activeNotes[channel][a] = 0
                BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_NOTE, a)
            }
            EVENT_CONTROL_CHANGE -> {
                channelStates[channel].controllers[a] = b
                when (a) {
                    0 -> channelStates[channel].bankMsb = b
                    32 -> channelStates[channel].bankLsb = b
                }
                sendControl(channel, a, b)
            }
            EVENT_PROGRAM_CHANGE -> {
                channelStates[channel].program = a
                BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_PROGRAM, a)
            }
            EVENT_PITCH_BEND -> {
                channelStates[channel].pitch = b
                BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_PITCH, b)
            }
            EVENT_CHANNEL_PRESSURE -> {
                channelStates[channel].channelPressure = a
                BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_CHANPRES, a)
            }
            EVENT_POLY_PRESSURE -> {
                BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_KEYPRES, a or (b shl 8))
            }
        }
    }

    private fun applyEventState(index: Int) {
        val packed = parsedMidi.eventData[index]
        val type = packed ushr EVENT_TYPE_SHIFT
        val channel = (packed ushr EVENT_CHANNEL_SHIFT) and EVENT_CHANNEL_MASK
        val a = (packed ushr EVENT_A_SHIFT) and EVENT_A_MASK
        val b = packed and EVENT_B_MASK
        when (type) {
            EVENT_NOTE_ON -> activeNotes[channel][a] = b
            EVENT_NOTE_OFF -> activeNotes[channel][a] = 0
            EVENT_CONTROL_CHANGE -> {
                channelStates[channel].controllers[a] = b
                when (a) {
                    0 -> channelStates[channel].bankMsb = b
                    32 -> channelStates[channel].bankLsb = b
                }
            }
            EVENT_PROGRAM_CHANGE -> channelStates[channel].program = a
            EVENT_PITCH_BEND -> channelStates[channel].pitch = b
            EVENT_CHANNEL_PRESSURE -> channelStates[channel].channelPressure = a
            EVENT_POLY_PRESSURE -> Unit
        }
    }

    private fun applyCurrentStateToStream() {
        for (channel in 0 until 16) {
            applySnapshot(channel, channelStates[channel], activeNotes[channel])
        }
    }

    private fun applySnapshot(channel: Int, snapshot: ChannelSnapshot, notes: IntArray) {
        if (snapshot.bankMsb >= 0) {
            BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_BANK, snapshot.bankMsb)
        }
        if (snapshot.bankLsb >= 0) {
            BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_BANK_LSB, snapshot.bankLsb)
        }
        if (snapshot.program >= 0) {
            BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_PROGRAM, snapshot.program)
        }
        for (cc in snapshot.controllers.indices) {
            val value = snapshot.controllers[cc]
            if (value >= 0) {
                sendControl(channel, cc, value)
            }
        }
        if (snapshot.channelPressure >= 0) {
            BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_CHANPRES, snapshot.channelPressure)
        }
        if (snapshot.pitch >= 0) {
            BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_PITCH, snapshot.pitch)
        }
        for (note in notes.indices) {
            val velocity = notes[note]
            if (velocity > 0) {
                BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, BASSMIDI.MIDI_EVENT_NOTE, note or (velocity shl 8))
            }
        }
    }

    private fun clearPlaybackState() {
        for (channel in 0 until 16) {
            channelStates[channel].clear()
            activeNotes[channel].fill(0)
        }
    }

    private fun sendControl(channel: Int, controller: Int, value: Int) {
        val eventType = when (controller) {
            0 -> BASSMIDI.MIDI_EVENT_BANK
            1 -> BASSMIDI.MIDI_EVENT_MODULATION
            7 -> BASSMIDI.MIDI_EVENT_VOLUME
            10 -> BASSMIDI.MIDI_EVENT_PAN
            11 -> BASSMIDI.MIDI_EVENT_EXPRESSION
            32 -> BASSMIDI.MIDI_EVENT_BANK_LSB
            64 -> BASSMIDI.MIDI_EVENT_SUSTAIN
            66 -> BASSMIDI.MIDI_EVENT_SOSTENUTO
            91 -> BASSMIDI.MIDI_EVENT_REVERB
            93 -> BASSMIDI.MIDI_EVENT_CHORUS
            120 -> BASSMIDI.MIDI_EVENT_SOUNDOFF
            121 -> BASSMIDI.MIDI_EVENT_RESET
            123 -> BASSMIDI.MIDI_EVENT_NOTESOFF
            else -> BASSMIDI.MIDI_EVENT_CONTROL
        }
        val param = if (eventType == BASSMIDI.MIDI_EVENT_CONTROL) controller or (value shl 8) else value
        BASSMIDI.BASS_MIDI_StreamEvent(streamHandle, channel, eventType, param)
    }
}

internal data class ParsedMidiTimeline(
    val eventTicks: IntArray,
    val eventTimesMs: LongArray,
    val eventData: IntArray,
    val eventCount: Int,
    val durationMs: Long,
    val loopPoint: LoopPoint
) {
    fun findLastEventIndexAtOrBefore(positionMs: Long): Int {
        if (eventCount == 0) return -1
        var low = 0
        var high = eventCount - 1
        var best = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (eventTimesMs[mid] <= positionMs) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    companion object {
        fun parse(bytes: ByteArray): ParsedMidiTimeline {
            val parser = SmfEventParser(bytes)
            return parser.parse()
        }
    }
}

internal class ChannelSnapshot {
    var bankMsb: Int = -1
    var bankLsb: Int = -1
    var program: Int = -1
    var pitch: Int = -1
    var channelPressure: Int = -1
    val controllers: IntArray = IntArray(128) { -1 }

    fun clear() {
        bankMsb = -1
        bankLsb = -1
        program = -1
        pitch = -1
        channelPressure = -1
        controllers.fill(-1)
    }
}

private class SmfEventParser(private val bytes: ByteArray) {
    private val reader = EventSmfReader(bytes)
    private val eventKeys = LongListBuilder()
    private val eventData = IntListBuilder()
    private val tempoKeys = LongListBuilder()
    private val tempoValues = IntListBuilder()
    private var order = 0

    fun parse(): ParsedMidiTimeline {
        if (!reader.canRead(14) || reader.readAscii4() != "MThd") {
            throw IllegalArgumentException("Invalid MIDI header")
        }
        val headerLen = reader.readU32()
        if (headerLen < 6 || !reader.canRead(headerLen)) {
            throw IllegalArgumentException("Invalid MIDI header length")
        }
        reader.readU16()
        val trackCount = reader.readU16()
        val division = reader.readU16()
        if ((division and 0x8000) != 0) {
            throw IllegalArgumentException("SMPTE MIDI timing is not supported")
        }
        if (headerLen > 6) reader.skip(headerLen - 6)

        val loopPoint = LoopPoint()
        var maxTick = 0

        repeat(trackCount) {
            if (!reader.canRead(8) || reader.readAscii4() != "MTrk") return@repeat
            val trackLength = reader.readU32()
            if (!reader.canRead(trackLength)) {
                reader.skip(trackLength)
                return@repeat
            }
            val trackEnd = reader.pos + trackLength
            var tick = 0
            var runningStatus = -1

            while (reader.pos < trackEnd && reader.canRead(1)) {
                tick += reader.readVarLen()
                if (tick > maxTick) maxTick = tick
                if (!reader.canRead(1)) break
                var status = reader.readU8()
                var firstData = -1
                if (status < 0x80) {
                    if (runningStatus < 0x80) break
                    firstData = status
                    status = runningStatus
                } else if (status < 0xF0) {
                    runningStatus = status
                } else {
                    runningStatus = -1
                }

                when {
                    status == 0xFF -> {
                        if (!reader.canRead(1)) break
                        val metaType = reader.readU8()
                        val metaLength = reader.readVarLen()
                        if (!reader.canRead(metaLength)) break
                        if (metaType == 0x51 && metaLength >= 3) {
                            val usPerQuarter =
                                (reader.readU8() shl 16) or
                                    (reader.readU8() shl 8) or
                                    reader.readU8()
                            if (metaLength > 3) reader.skip(metaLength - 3)
                            addTempo(tick, usPerQuarter)
                        } else {
                            reader.skip(metaLength)
                        }
                    }
                    status == 0xF0 || status == 0xF7 -> {
                        val syxLength = reader.readVarLen()
                        if (!reader.canRead(syxLength)) break
                        reader.skip(syxLength)
                    }
                    status in 0x80..0xEF -> {
                        val eventType = status and 0xF0
                        val channel = status and 0x0F
                        val a = if (firstData >= 0) firstData else reader.readU8()
                        val b = if (eventType != 0xC0 && eventType != 0xD0) reader.readU8() else 0
                        when (eventType) {
                            0x80 -> addEvent(tick, packEvent(EVENT_NOTE_OFF, channel, a and 0x7F, 0))
                            0x90 -> {
                                val note = a and 0x7F
                                val velocity = b and 0x7F
                                addEvent(tick, packEvent(if (velocity == 0) EVENT_NOTE_OFF else EVENT_NOTE_ON, channel, note, velocity))
                            }
                            0xA0 -> addEvent(tick, packEvent(EVENT_POLY_PRESSURE, channel, a and 0x7F, b and 0x7F))
                            0xB0 -> {
                                val controller = a and 0x7F
                                val value = b and 0x7F
                                if (controller == 111 && (!loopPoint.hasLoopStartMarker || tick < loopPoint.startTick)) {
                                    loopPoint.hasLoopStartMarker = true
                                    loopPoint.startTick = tick
                                }
                                addEvent(tick, packEvent(EVENT_CONTROL_CHANGE, channel, controller, value))
                            }
                            0xC0 -> addEvent(tick, packEvent(EVENT_PROGRAM_CHANGE, channel, a and 0x7F, 0))
                            0xD0 -> addEvent(tick, packEvent(EVENT_CHANNEL_PRESSURE, channel, a and 0x7F, 0))
                            0xE0 -> {
                                val value = (a and 0x7F) or ((b and 0x7F) shl 7)
                                addEvent(tick, packEvent(EVENT_PITCH_BEND, channel, 0, value))
                            }
                        }
                    }
                    else -> Unit
                }
            }
            if (reader.pos < trackEnd) reader.pos = trackEnd
        }

        val eventKeysArray = eventKeys.toArray()
        val eventDataArray = eventData.toArray()
        sortPairs(eventKeysArray, eventDataArray)

        val tempoKeysArray = tempoKeys.toArray()
        val tempoValuesArray = tempoValues.toArray()
        if (tempoKeysArray.isNotEmpty()) sortPairs(tempoKeysArray, tempoValuesArray)

        val eventCount = eventDataArray.size
        val eventTicks = IntArray(eventCount)
        val eventTimesMs = LongArray(eventCount)
        fillEventTimes(
            division = division,
            eventKeys = eventKeysArray,
            eventTicks = eventTicks,
            eventTimesMs = eventTimesMs,
            tempoKeys = tempoKeysArray,
            tempoValues = tempoValuesArray
        )

        loopPoint.endTick = maxTick
        loopPoint.endMs = tickToMs(maxTick, division, tempoKeysArray, tempoValuesArray)
        if (loopPoint.hasLoopStartMarker) {
            loopPoint.startMs = tickToMs(loopPoint.startTick, division, tempoKeysArray, tempoValuesArray)
        }
        val durationMs = loopPoint.endMs.coerceAtLeast(eventTimesMs.lastOrNull() ?: 0L)

        return ParsedMidiTimeline(
            eventTicks = eventTicks,
            eventTimesMs = eventTimesMs,
            eventData = eventDataArray,
            eventCount = eventCount,
            durationMs = durationMs,
            loopPoint = loopPoint
        )
    }

    private fun addEvent(tick: Int, packed: Int) {
        eventKeys.add((tick.toLong() shl 32) or (order++.toLong() and 0xFFFF_FFFFL))
        eventData.add(packed)
    }

    private fun addTempo(tick: Int, usPerQuarter: Int) {
        tempoKeys.add((tick.toLong() shl 32) or (order++.toLong() and 0xFFFF_FFFFL))
        tempoValues.add(usPerQuarter)
    }
}

private class EventSmfReader(private val bytes: ByteArray) {
    var pos: Int = 0

    fun canRead(count: Int): Boolean = pos + count <= bytes.size

    fun readU8(): Int = bytes[pos++].toInt() and 0xFF

    fun readU16(): Int = (readU8() shl 8) or readU8()

    fun readU32(): Int = (readU8() shl 24) or (readU8() shl 16) or (readU8() shl 8) or readU8()

    fun readAscii4(): String = String(bytes, pos, 4).also { pos += 4 }

    fun skip(count: Int) {
        pos = (pos + count).coerceAtMost(bytes.size)
    }

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

private class IntListBuilder(initialCapacity: Int = 256) {
    private var values = IntArray(initialCapacity)
    var size: Int = 0
        private set

    fun add(value: Int) {
        ensureCapacity(size + 1)
        values[size++] = value
    }

    fun toArray(): IntArray = values.copyOf(size)

    private fun ensureCapacity(targetSize: Int) {
        if (targetSize <= values.size) return
        var next = values.size.coerceAtLeast(1)
        while (next < targetSize) next = next shl 1
        values = values.copyOf(next)
    }
}

private class LongListBuilder(initialCapacity: Int = 256) {
    private var values = LongArray(initialCapacity)
    var size: Int = 0
        private set

    fun add(value: Long) {
        ensureCapacity(size + 1)
        values[size++] = value
    }

    fun toArray(): LongArray = values.copyOf(size)

    private fun ensureCapacity(targetSize: Int) {
        if (targetSize <= values.size) return
        var next = values.size.coerceAtLeast(1)
        while (next < targetSize) next = next shl 1
        values = values.copyOf(next)
    }
}

private fun fillEventTimes(
    division: Int,
    eventKeys: LongArray,
    eventTicks: IntArray,
    eventTimesMs: LongArray,
    tempoKeys: LongArray,
    tempoValues: IntArray
) {
    var tempoIndex = 0
    var currentTempoUsPerQuarter = 500_000
    var accumulatedUs = 0L
    var lastTick = 0

    for (index in eventKeys.indices) {
        val eventTick = (eventKeys[index] ushr 32).toInt()
        while (tempoIndex < tempoKeys.size && ((tempoKeys[tempoIndex] ushr 32).toInt() <= eventTick)) {
            val tempoTick = (tempoKeys[tempoIndex] ushr 32).toInt()
            val deltaTick = (tempoTick - lastTick).coerceAtLeast(0)
            accumulatedUs += deltaTick.toLong() * currentTempoUsPerQuarter.toLong() / division.toLong()
            lastTick = tempoTick
            currentTempoUsPerQuarter = tempoValues[tempoIndex]
            tempoIndex += 1
        }
        val deltaTick = (eventTick - lastTick).coerceAtLeast(0)
        val eventUs = accumulatedUs + deltaTick.toLong() * currentTempoUsPerQuarter.toLong() / division.toLong()
        eventTicks[index] = eventTick
        eventTimesMs[index] = eventUs / 1000L
    }
}

private fun tickToMs(tick: Int, division: Int, tempoKeys: LongArray, tempoValues: IntArray): Long {
    var tempoIndex = 0
    var currentTempoUsPerQuarter = 500_000
    var accumulatedUs = 0L
    var lastTick = 0
    while (tempoIndex < tempoKeys.size && ((tempoKeys[tempoIndex] ushr 32).toInt() <= tick)) {
        val tempoTick = (tempoKeys[tempoIndex] ushr 32).toInt()
        val deltaTick = (tempoTick - lastTick).coerceAtLeast(0)
        accumulatedUs += deltaTick.toLong() * currentTempoUsPerQuarter.toLong() / division.toLong()
        lastTick = tempoTick
        currentTempoUsPerQuarter = tempoValues[tempoIndex]
        tempoIndex += 1
    }
    val remainingTick = (tick - lastTick).coerceAtLeast(0)
    accumulatedUs += remainingTick.toLong() * currentTempoUsPerQuarter.toLong() / division.toLong()
    return accumulatedUs / 1000L
}

private fun sortPairs(keys: LongArray, values: IntArray) {
    if (keys.size <= 1) return
    quickSort(keys, values, 0, keys.lastIndex)
}

private fun quickSort(keys: LongArray, values: IntArray, low: Int, high: Int) {
    var left = low
    var right = high
    val pivot = keys[(low + high) ushr 1]
    while (left <= right) {
        while (keys[left] < pivot) left++
        while (keys[right] > pivot) right--
        if (left <= right) {
            val key = keys[left]
            keys[left] = keys[right]
            keys[right] = key
            val value = values[left]
            values[left] = values[right]
            values[right] = value
            left++
            right--
        }
    }
    if (low < right) quickSort(keys, values, low, right)
    if (left < high) quickSort(keys, values, left, high)
}

private fun packEvent(type: Int, channel: Int, a: Int, b: Int): Int {
    return (type shl EVENT_TYPE_SHIFT) or
        ((channel and EVENT_CHANNEL_MASK) shl EVENT_CHANNEL_SHIFT) or
        ((a and EVENT_A_MASK) shl EVENT_A_SHIFT) or
        (b and EVENT_B_MASK)
}

private const val EVENT_TYPE_SHIFT = 28
private const val EVENT_CHANNEL_SHIFT = 24
private const val EVENT_A_SHIFT = 14
private const val EVENT_CHANNEL_MASK = 0x0F
private const val EVENT_A_MASK = 0x7F
private const val EVENT_B_MASK = 0x3FFF

private const val EVENT_NOTE_OFF = 0
private const val EVENT_NOTE_ON = 1
private const val EVENT_CONTROL_CHANGE = 2
private const val EVENT_PROGRAM_CHANGE = 3
private const val EVENT_PITCH_BEND = 4
private const val EVENT_CHANNEL_PRESSURE = 5
private const val EVENT_POLY_PRESSURE = 6
