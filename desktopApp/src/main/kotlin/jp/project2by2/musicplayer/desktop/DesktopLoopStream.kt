package jp.project2by2.musicplayer.desktop

import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Native
import com.sun.jna.Memory
import com.sun.jna.ptr.FloatByReference
import jp.project2by2.musicplayer.audio.*
import jp.project2by2.musicplayer.platform.currentPlatform
import java.io.File
import java.nio.ByteBuffer

internal fun createDesktopLoopStream(
    bass: BassCore, midi: BassMidi, file: File, font: Int, decodeOutput: Boolean = false,
): MidiLoopStream {
    val first = DesktopMidiDecoder(bass, midi, file, font)
    val second = try { DesktopMidiDecoder(bass, midi, file, font) }
    catch (error: Throwable) { first.close(); throw error }
    return MidiLoopStream(DesktopLoopOutput(bass, decodeOutput), first, second) {
        System.err.println("MIDI loop render failed: ${it.message}")
    }
}

internal class DesktopLoopOutput(private val bass: BassCore, private val decode: Boolean) : LoopOutputBackend {
    private var callback: PcmStreamProc? = null
    override fun create(render: (ByteBuffer, Int) -> Int): Int {
        val proc = PcmStreamProc { _, buffer, length, _ -> render(buffer.getByteBuffer(0, length.toLong()), length) }
        // MIDI filter callbacks re-enter Java while this PCM callback is still on the stack.
        // Keep BASS workers attached as daemons; nested callbacks must not detach the thread.
        Native.setCallbackThreadInitializer(proc, CallbackThreadInitializer(true, false, "bass-audio"))
        callback = proc
        return bass.BASS_StreamCreate(44100, 2, 0x100 or if (decode) 0x200000 else 0, proc, null)
    }
    override fun lock(handle: Int, locked: Boolean) { check(bass.BASS_ChannelLock(handle, locked)) }
    override fun positionBytes(handle: Int) = bass.BASS_ChannelGetPosition(handle, 0)
    override fun reset(handle: Int) {
        check(bass.BASS_ChannelSetPosition(handle, 0, 0))
    }
    override fun free(handle: Int) {
        check(bass.BASS_StreamFree(handle))
        callback = null
    }
}

internal class DesktopMidiDecoder(
    private val bass: BassCore, private val midi: BassMidi, file: File, font: Int,
) : NativeMidiDecoder {
    private var handle = 0
    private var tail = false
    private val filter = MidiFilter { _, _, _, _, _ -> !tail }
    private val buffer = Memory(TailLoopRenderer.BLOCK_FRAMES * 8L)
    override val durationFrames: Long
    override val durationTicks: Long
    private val channels: Int

    init {
        try {
            val unicode = if (currentPlatform.isWindows) Int.MIN_VALUE else 0
            val bytes = (file.absolutePath + '\u0000').toByteArray(if (unicode != 0) Charsets.UTF_16LE else Charsets.UTF_8)
            handle = Memory(bytes.size.toLong()).use {
                it.write(0, bytes, 0, bytes.size)
                midi.BASS_MIDI_StreamCreateFile(0, it, 0, 0, unicode or 0x200000 or 0x100 or 0x8000 or 0x1000, 44100)
            }
            check(handle != 0) { "Cannot open MIDI: ${bass.BASS_ErrorGetCode()}" }
            val length = bass.BASS_ChannelGetLength(handle, 0)
            durationTicks = bass.BASS_ChannelGetLength(handle, 2)
            check(length >= 0 && durationTicks >= 0)
            durationFrames = length / 8
            val count = FloatByReference()
            check(bass.BASS_ChannelGetAttribute(handle, 0x12002, count))
            channels = count.value.toInt()
            setFont(font)
            check(midi.BASS_MIDI_StreamLoadSamples(handle))
            Native.setCallbackThreadInitializer(filter, CallbackThreadInitializer(true, false, "bass-midi"))
            check(midi.BASS_MIDI_StreamSetFilter(handle, true, filter, null))
        } catch (error: Throwable) { close(); throw error }
    }

    override fun read(samples: FloatArray, frames: Int): Int {
        val bytes = bass.BASS_ChannelGetData(handle, buffer, frames * 8)
        if (bytes == -1 && bass.BASS_ErrorGetCode() == 45) return 0 // BASS_ERROR_ENDED
        check(bytes >= 0 && bytes % 8 == 0) { "MIDI decode failed: ${bass.BASS_ErrorGetCode()}" }
        buffer.read(0, samples, 0, bytes / 4)
        return bytes / 8
    }
    override fun prepare(frame: Long) {
        tail = false
        check(bass.BASS_ChannelSetPosition(handle, frame.coerceAtMost((durationFrames - 1).coerceAtLeast(0)) * 8, 0x1000000)) // BASS_POS_FLUSH
    }
    override fun frameAtTick(tick: Long): Long {
        if (tick >= durationTicks) return durationFrames
        tail = false
        check(bass.BASS_ChannelSetPosition(handle, tick, 2)) { "Tick seek $tick failed: ${bass.BASS_ErrorGetCode()}" }
        return bass.BASS_ChannelGetPosition(handle, 0) / 8
    }
    override fun beginTail() {
        tail = true
        repeat(channels) { channel ->
            // Release pedals as well as keys; never send SOUNDOFF or SYSTEM reset.
            check(midi.BASS_MIDI_StreamEvent(handle, channel, 15, 0))
            check(midi.BASS_MIDI_StreamEvent(handle, channel, 76, 0))
            check(midi.BASS_MIDI_StreamEvent(handle, channel, 18, 0))
        }
    }
    override fun setAttribute(attribute: Int, value: Float) { check(bass.BASS_ChannelSetAttribute(handle, attribute, value)) }
    override fun setFlags(flags: Int, mask: Int) { check(bass.BASS_ChannelFlags(handle, flags, mask) != -1) }
    override fun setFont(font: Int) {
        Memory(12).use {
            it.setInt(0, font); it.setInt(4, -1); it.setInt(8, 0)
            check(midi.BASS_MIDI_StreamSetFonts(handle, it, 1))
        }
    }
    override fun close() {
        if (handle != 0) { bass.BASS_StreamFree(handle); handle = 0 }
        buffer.close()
    }
}
