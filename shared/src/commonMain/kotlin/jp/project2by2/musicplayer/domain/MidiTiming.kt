package jp.project2by2.musicplayer

import dev.atsushieno.ktmidi.Midi1CompoundMessage
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.read

/** File timing, including the silence between the last note and each track's EOT. */
data class MidiTiming(val endTick: Int, val endMs: Long, val loopStartTick: Int?, val loopStartMs: Long?)

fun parseMidiTiming(bytes: ByteArray): MidiTiming =
    Midi1Music().apply { read(bytes.toList()) }.extractMidiTiming()

internal fun Midi1Music.extractMidiTiming(): MidiTiming {
    data class Tempo(val tick: Int, val micros: Int)
    val tempos = mutableListOf<Tempo>()
    var endTick = 0
    var loopTick: Int? = null
    for (track in tracks) {
        var tick = 0
        for (event in track.events) {
            require(event.deltaTime >= 0 && tick <= Int.MAX_VALUE - event.deltaTime)
            tick += event.deltaTime
            val message = event.message
            val status = message.statusByte.toInt() and 255
            val type = message.msb.toInt() and 255
            if (status == 0xFF && type == 0x2F) break
            if (status and 0xF0 == 0xB0 && type == 111) loopTick = minOf(loopTick ?: tick, tick)
            if (status == 0xFF && type == 0x51) {
                val data = (message as? Midi1CompoundMessage)?.extraData ?: continue
                if (data.size == 3) {
                    val tempo = data.fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 255) }
                    require(tempo > 0)
                    tempos += Tempo(tick, tempo)
                }
            }
        }
        // Missing EOT: fall back to the last event in this track.
        endTick = maxOf(endTick, tick)
    }
    val division = deltaTimeSpec and 0xFFFF
    require(division != 0)
    val sorted = tempos.sortedBy { it.tick }
    fun millisAt(target: Int): Long {
        if (division and 0x8000 != 0) {
            val fpsCode = 256 - (division ushr 8)
            val ticksPerFrame = division and 255
            require(fpsCode in listOf(24, 25, 29, 30) && ticksPerFrame > 0)
            // SMPTE -29 is 30000/1001 fps, independent of tempo meta events.
            return if (fpsCode == 29) target.toLong() * 1_001_000 / (30_000L * ticksPerFrame)
            else target.toLong() * 1000 / (fpsCode.toLong() * ticksPerFrame)
        }
        var tick = 0
        var tempo = 500_000
        var numerator = 0L
        for (event in sorted) {
            if (event.tick > target) break
            numerator += (event.tick - tick).toLong() * tempo
            tick = event.tick
            tempo = event.micros
        }
        numerator += (target - tick).toLong() * tempo
        // Divide only once, avoiding per-event or per-tempo rounding drift.
        return numerator / (division.toLong() * 1000)
    }
    return MidiTiming(endTick, millisAt(endTick), loopTick, loopTick?.let(::millisAt))
}
