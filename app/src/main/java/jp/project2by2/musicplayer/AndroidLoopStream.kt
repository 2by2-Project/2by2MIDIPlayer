package jp.project2by2.musicplayer

import android.util.Log
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSMIDI
import jp.project2by2.musicplayer.audio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** JNI adapter for the same two-synth renderer used by Windows/Linux. */
internal fun createAndroidLoopStream(path: String, font: Int): MidiLoopStream {
    val first = AndroidMidiDecoder(path, font)
    val second = try { AndroidMidiDecoder(path, font) }
    catch (error: Throwable) { first.close(); throw error }
    return MidiLoopStream(AndroidLoopOutput(), first, second) { Log.e("MidiLoop", "Audio rendering failed", it) }
}

private class AndroidLoopOutput : LoopOutputBackend {
    private var callback: BASS.STREAMPROC? = null
    override fun create(render: (ByteBuffer, Int) -> Int): Int {
        val proc = BASS.STREAMPROC { _, buffer, length, _ -> render(buffer, length) }
        callback = proc
        return BASS.BASS_StreamCreate(44100, 2, BASS.BASS_SAMPLE_FLOAT, proc, null)
    }
    override fun lock(handle: Int, locked: Boolean) { check(BASS.BASS_ChannelLock(handle, locked)) }
    override fun positionBytes(handle: Int) = BASS.BASS_ChannelGetPosition(handle, BASS.BASS_POS_BYTE)
    override fun reset(handle: Int) {
        check(BASS.BASS_ChannelSetPosition(handle, 0, BASS.BASS_POS_BYTE))
    }
    override fun free(handle: Int) {
        check(BASS.BASS_StreamFree(handle))
        callback = null
    }
}

private class AndroidMidiDecoder(path: String, font: Int) : NativeMidiDecoder {
    private var handle = 0
    private var tail = false
    private val filter = BASSMIDI.MIDIFILTERPROC { _, _, _, _, _ -> !tail }
    private val buffer = ByteBuffer.allocateDirect(TailLoopRenderer.BLOCK_FRAMES * 8).order(ByteOrder.nativeOrder())
    override val durationFrames: Long
    override val durationTicks: Long
    private val channels: Int

    init {
        try {
            handle = BASSMIDI.BASS_MIDI_StreamCreateFile(path, 0, 0,
                BASS.BASS_STREAM_DECODE or BASS.BASS_SAMPLE_FLOAT or BASSMIDI.BASS_MIDI_NOCROP or BASSMIDI.BASS_MIDI_DECAYEND, 44100)
            check(handle != 0) { "Cannot open MIDI: ${BASS.BASS_ErrorGetCode()}" }
            val length = BASS.BASS_ChannelGetLength(handle, BASS.BASS_POS_BYTE)
            durationTicks = BASS.BASS_ChannelGetLength(handle, BASSMIDI.BASS_POS_MIDI_TICK)
            check(length >= 0 && durationTicks >= 0)
            durationFrames = length / 8
            val count = BASS.FloatValue()
            check(BASS.BASS_ChannelGetAttribute(handle, BASSMIDI.BASS_ATTRIB_MIDI_CHANS, count))
            channels = count.value.toInt()
            setFont(font)
            check(BASSMIDI.BASS_MIDI_StreamLoadSamples(handle))
            check(BASSMIDI.BASS_MIDI_StreamSetFilter(handle, true, filter, null))
        } catch (error: Throwable) { close(); throw error }
    }

    override fun read(samples: FloatArray, frames: Int): Int {
        buffer.clear()
        val bytes = BASS.BASS_ChannelGetData(handle, buffer, frames * 8)
        if (bytes == -1 && BASS.BASS_ErrorGetCode() == BASS.BASS_ERROR_ENDED) return 0
        check(bytes >= 0 && bytes % 8 == 0) { "MIDI decode failed: ${BASS.BASS_ErrorGetCode()}" }
        for (i in 0 until bytes / 4) samples[i] = buffer.getFloat(i * 4)
        return bytes / 8
    }
    override fun prepare(frame: Long) {
        tail = false
        val target = frame.coerceAtMost((durationFrames - 1).coerceAtLeast(0))
        check(BASS.BASS_ChannelSetPosition(handle, target * 8, BASS.BASS_POS_BYTE or BASS.BASS_POS_FLUSH))
    }
    override fun frameAtTick(tick: Long): Long {
        if (tick >= durationTicks) return durationFrames
        tail = false
        check(BASS.BASS_ChannelSetPosition(handle, tick, BASSMIDI.BASS_POS_MIDI_TICK))
        return BASS.BASS_ChannelGetPosition(handle, BASS.BASS_POS_BYTE) / 8
    }
    override fun beginTail() {
        tail = true
        repeat(channels) { channel ->
            check(BASSMIDI.BASS_MIDI_StreamEvent(handle, channel, BASSMIDI.MIDI_EVENT_SUSTAIN, 0))
            check(BASSMIDI.BASS_MIDI_StreamEvent(handle, channel, BASSMIDI.MIDI_EVENT_SOSTENUTO, 0))
            check(BASSMIDI.BASS_MIDI_StreamEvent(handle, channel, BASSMIDI.MIDI_EVENT_NOTESOFF, 0))
        }
    }
    override fun setAttribute(attribute: Int, value: Float) { check(BASS.BASS_ChannelSetAttribute(handle, attribute, value)) }
    override fun setFlags(flags: Int, mask: Int) { check(BASS.BASS_ChannelFlags(handle, flags, mask) != -1L) }
    override fun setFont(font: Int) {
        val fonts = arrayOf(BASSMIDI.BASS_MIDI_FONT().apply { this.font = font; preset = -1; bank = 0 })
        check(BASSMIDI.BASS_MIDI_StreamSetFonts(handle, fonts, 1))
    }
    override fun close() { if (handle != 0) { BASS.BASS_StreamFree(handle); handle = 0 } }
}
