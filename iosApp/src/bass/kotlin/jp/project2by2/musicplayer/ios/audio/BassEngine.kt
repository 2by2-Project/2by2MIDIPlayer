@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package jp.project2by2.musicplayer.ios.audio

import jp.project2by2.musicplayer.audio.LoopDecoder
import jp.project2by2.musicplayer.audio.TailLoopRenderer
import jp.project2by2.musicplayer.ios.bass.*
import kotlinx.cinterop.*
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val SAMPLE_RATE = 44100
private const val STREAM_END = 0x80000000u // BASS_STREAMPROC_END
private const val POSITION_ERROR = ULong.MAX_VALUE // (QWORD)-1
private const val DATA_ERROR = UInt.MAX_VALUE // (DWORD)-1

private fun bassError(): Int = BASS_ErrorGetCode()

/**
 * One BASS output device for the player and the loop editor's preview (Android's BassRuntime).
 * BASS keeps the current device per thread, so every engine selects it before creating streams.
 */
private object BassDevice {
    private val lock = platform.Foundation.NSRecursiveLock()
    private var users = 0
    private var device = 0u

    fun acquire() {
        lock.lock()
        try {
            if (users == 0) {
                check(BASS_GetVersion() shr 16 == 0x0204u && BASS_MIDI_GetVersion() shr 16 == 0x0204u) { "BASS/BASSMIDI 2.4 is required" }
                // The app configures AVAudioSession (playback category) itself; see AudioSessionController.
                BASS_SetConfig(BASS_CONFIG_IOS_NOCATEGORY.toUInt(), 1u)
                BASS_SetConfig(BASS_CONFIG_MIDI_COMPACT.toUInt(), 0u)
                check(BASS_Init(-1, SAMPLE_RATE.toUInt(), 0u, null, null) != 0) { "Cannot start audio output (BASS ${bassError()})" }
                device = BASS_GetDevice()
            }
            users += 1
        } finally {
            lock.unlock()
        }
    }

    fun select() {
        if (device != 0u && device != UInt.MAX_VALUE) BASS_SetDevice(device)
    }

    fun release() {
        lock.lock()
        try {
            if (users > 0) {
                users -= 1
                if (users == 0) BASS_Free()
            }
        } finally {
            lock.unlock()
        }
    }
}

/**
 * BASS output and decoders through the official C API (bass.xcframework / bassmidi.xcframework).
 * The same renderer, decoder contract and settings as Android (JNI) and desktop (JNA).
 */
class BassEngine : MidiEngine {
    private var initialized = false
    private var font = 0u
    private var stream: IosLoopStream? = null
    private var maxVoices = 40
    private var effectsEnabled = false
    private var reverbStrength = 1f

    override fun initialize(): String {
        if (!initialized) {
            BassDevice.acquire()
            initialized = true
        }
        BassDevice.select()
        return "BASS ${version(BASS_GetVersion())} · BASSMIDI ${version(BASS_MIDI_GetVersion())}"
    }

    override val hasSoundFont: Boolean get() = font != 0u

    override fun setSoundFont(path: String) {
        initialize()
        val next = BASS_MIDI_FontInit(path.cstr, 0u)
        check(next != 0u) { "Cannot load SoundFont (BASS ${bassError()})" }
        try {
            stream?.setFont(next)
        } catch (e: Exception) {
            try {
                if (font != 0u) stream?.setFont(font)
            } catch (restore: Exception) {
                // Stop both decoders before freeing a font that one of them may still use.
                unload()
                e.addSuppressed(restore)
            }
            BASS_MIDI_FontFree(next)
            throw e
        }
        val previous = font
        font = next
        if (previous != 0u) BASS_MIDI_FontFree(previous)
    }

    override fun load(path: String, loop: LoopRange, boundary: LoopBoundary) {
        initialize()
        check(font != 0u) { "Choose a SoundFont in Settings" }
        val first = IosMidiDecoder(path, font)
        val second = try { IosMidiDecoder(path, font) } catch (e: Throwable) { first.close(); throw e }
        val next = IosLoopStream(first, second)
        try {
            applySynthSettings(next)
            next.configure(loop, boundary)
            check(BASS_ChannelSetAttribute(next.output, BASS_ATTRIB_VOL.toUInt(), 1f) != 0) { "Cannot set volume (BASS ${bassError()})" }
        } catch (e: Exception) {
            next.close()
            throw e
        }
        stream?.close()
        stream = next
    }

    override fun configureLoop(loop: LoopRange, boundary: LoopBoundary) {
        stream?.configure(loop, boundary)
    }

    override val loaded: Boolean get() = stream != null

    override fun play() {
        val current = stream ?: return
        // An interruption (call, Siri) stops the device output; restart it before the channel.
        if (BASS_IsStarted() != 1u) BASS_Start()
        if (current.positionMs() >= current.durationMs) current.seek(0)
        check(BASS_ChannelPlay(current.output, 0) != 0) { "Cannot start playback (BASS ${bassError()})" }
    }

    override fun pause() {
        stream?.let { BASS_ChannelPause(it.output) }
    }

    override fun seek(ms: Long) {
        stream?.seek(ms)
    }

    override fun position(): EnginePosition {
        val current = stream ?: return EnginePosition()
        current.failure.load()?.let { throw IllegalStateException("MIDI rendering failed", it) }
        return EnginePosition(current.positionMs(), current.durationMs,
            BASS_ChannelIsActive(current.output) == BASS_ACTIVE_PLAYING.toUInt())
    }

    override fun audibleLoopCount(): Long = stream?.audibleLoopCount() ?: 0L

    override fun synthSettings(voices: Int, effects: Boolean, reverb: Float) {
        maxVoices = voices.coerceIn(1, 1000)
        effectsEnabled = effects
        reverbStrength = reverb.coerceIn(0f, 3f)
        stream?.let(::applySynthSettings)
    }

    private fun applySynthSettings(target: IosLoopStream) {
        target.setAttribute(BASS_ATTRIB_MIDI_VOICES, maxVoices.toFloat())
        target.setAttribute(BASS_ATTRIB_MIDI_REVERB, reverbStrength)
        target.setFlags(if (effectsEnabled) 0 else BASS_MIDI_NOFX, BASS_MIDI_NOFX)
    }

    override fun startFade(ms: Int) {
        val current = stream ?: return
        BASS_ChannelSlideAttribute(current.output, BASS_ATTRIB_VOL.toUInt(), 0f, ms.toUInt())
    }

    override fun cancelFade() {
        val current = stream ?: return
        // A zero-length slide replaces a running fade and restores the volume at once.
        BASS_ChannelSlideAttribute(current.output, BASS_ATTRIB_VOL.toUInt(), 1f, 0u)
    }

    override fun fadeFinished(): Boolean {
        val current = stream ?: return false
        if (BASS_ChannelIsSliding(current.output, BASS_ATTRIB_VOL.toUInt()) != 0) return false
        return memScoped {
            val volume = alloc<FloatVar>()
            BASS_ChannelGetAttribute(current.output, BASS_ATTRIB_VOL.toUInt(), volume.ptr) != 0 && volume.value <= 0.001f
        }
    }

    override fun unload() {
        stream?.close()
        stream = null
    }

    override fun close() {
        unload()
        if (font != 0u) BASS_MIDI_FontFree(font)
        font = 0u
        if (initialized) BassDevice.release()
        initialized = false
    }

    private fun version(number: UInt) = listOf(24, 16, 8, 0).joinToString(".") { ((number shr it) and 255u).toString() }
}

/** Render callback for the output stream; [user] is the stream's StableRef. */
private val streamProc = staticCFunction { _: UInt, buffer: COpaquePointer?, length: UInt, user: COpaquePointer? ->
    val target = user?.asStableRef<IosLoopStream>()?.get()
    if (target == null || buffer == null) STREAM_END else target.render(buffer, length.toInt())
}

/** Drops file events while a decoder releases its tail, as on Android and desktop. */
private val midiFilter = staticCFunction { _: UInt, _: Int, _: CPointer<BASS_MIDI_EVENT>?, _: Int, user: COpaquePointer? ->
    val decoder = user?.asStableRef<IosMidiDecoder>()?.get()
    if (decoder == null || !decoder.tail) 1 else 0
}

/**
 * Port of the JVM MidiLoopStream: one stable output handle around two decoders. Control calls come
 * from one thread and take the BASS channel lock; the render callback never calls back into them.
 */
private class IosLoopStream(private val first: IosMidiDecoder, private val second: IosMidiDecoder) {
    private val renderer: TailLoopRenderer
    private val samples = FloatArray(TailLoopRenderer.BLOCK_FRAMES * 2)
    private val self = StableRef.create(this)
    val durationFrames = first.durationFrames
    val durationTicks = first.durationTicks
    val durationMs get() = durationFrames * 1000 / SAMPLE_RATE
    var output = 0u
        private set
    val failure = AtomicReference<Throwable?>(null)

    init {
        try {
            require(first.durationFrames == second.durationFrames)
            renderer = TailLoopRenderer(first, second, durationFrames, SAMPLE_RATE)
            output = BASS_StreamCreate(SAMPLE_RATE.toUInt(), 2u, BASS_SAMPLE_FLOAT.toUInt(), streamProc, self.asCPointer())
            check(output != 0u) { "Cannot create loop audio output (BASS ${bassError()})" }
        } catch (error: Throwable) {
            self.dispose()
            second.close()
            first.close()
            throw error
        }
    }

    private fun outputFrame(): Long {
        val bytes = BASS_ChannelGetPosition(output, BASS_POS_BYTE.toUInt())
        return if (bytes == POSITION_ERROR) 0 else (bytes / 8u).toLong()
    }

    fun render(buffer: COpaquePointer, length: Int): UInt {
        if (failure.load() != null) return STREAM_END
        var bytes = 0
        try {
            // This is the consumed position. Prune old loop anchors even without UI polling.
            renderer.songFrame(outputFrame())
            val out = buffer.reinterpret<FloatVar>()
            while (bytes < length) {
                val wanted = minOf((length - bytes) / 8, TailLoopRenderer.BLOCK_FRAMES)
                if (wanted == 0) break
                val frames = renderer.render(samples, wanted)
                val base = bytes / 4
                for (i in 0 until frames * 2) out[base + i] = samples[i]
                bytes += frames * 8
                if (frames < wanted || renderer.ended) return bytes.toUInt() or STREAM_END
            }
            return bytes.toUInt()
        } catch (error: Throwable) {
            // Never let a Kotlin exception unwind through BASS's audio thread.
            failure.store(error)
            return bytes.toUInt() or STREAM_END
        }
    }

    fun positionMs(): Long = locked { renderer.songFrame(outputFrame()) * 1000 / SAMPLE_RATE }

    fun audibleLoopCount(): Long = locked {
        renderer.songFrame(outputFrame())
        renderer.audibleLoopCount
    }

    fun seek(ms: Long) = locked {
        renderer.seek(if (ms >= durationMs) durationFrames else ms.coerceAtLeast(0) * SAMPLE_RATE / 1000)
        // Discard mixed output too, including all old tail samples.
        BASS_ChannelSetPosition(output, 0u, BASS_POS_BYTE.toUInt())
        failure.store(null)
    }

    fun configure(loop: LoopRange, boundary: LoopBoundary) = locked {
        val resume = if (renderer.ended) renderer.songFrame(outputFrame()) else null
        when (loop) {
            is LoopRange.Ticks -> renderer.configureTicks(loop.start.coerceIn(0, durationTicks),
                (loop.end ?: durationTicks).coerceIn(0, durationTicks)) { boundary.repeat() }
            is LoopRange.Millis -> renderer.configure(loop.start.coerceIn(0, durationMs) * SAMPLE_RATE / 1000,
                if (loop.end >= durationMs) durationFrames else loop.end.coerceAtLeast(0) * SAMPLE_RATE / 1000) { boundary.repeat() }
        }
        // Extending an editor's stopped preview must be playable again without a separate seek.
        if (resume != null) {
            renderer.seek(resume)
            BASS_ChannelSetPosition(output, 0u, BASS_POS_BYTE.toUInt())
        }
    }

    fun setAttribute(attribute: Int, value: Float) = locked {
        first.setAttribute(attribute, value)
        second.setAttribute(attribute, value)
    }

    fun setFlags(flags: Int, mask: Int) = locked {
        first.setFlags(flags, mask)
        second.setFlags(flags, mask)
    }

    fun setFont(font: UInt) = locked {
        first.setFont(font)
        second.setFont(font)
    }

    private inline fun <T> locked(block: () -> T): T {
        check(output != 0u) { "MIDI output is closed" }
        BASS_ChannelLock(output, 1)
        try { return block() } finally { BASS_ChannelLock(output, 0) }
    }

    fun close() {
        if (output == 0u) return
        // StreamFree waits for the callback. Do not hold the channel lock here.
        BASS_StreamFree(output)
        output = 0u
        self.dispose()
        second.close()
        first.close()
    }
}

private class IosMidiDecoder(path: String, font: UInt) : LoopDecoder {
    private var handle = 0u
    private var disposed = false
    @Volatile var tail = false
    private val self = StableRef.create(this)
    private val buffer = nativeHeap.allocArray<FloatVar>(TailLoopRenderer.BLOCK_FRAMES * 2)
    val durationFrames: Long
    val durationTicks: Long
    private val channels: Int

    init {
        try {
            val flags = BASS_STREAM_DECODE or BASS_SAMPLE_FLOAT or BASS_MIDI_NOCROP or BASS_MIDI_DECAYEND
            handle = BASS_MIDI_StreamCreateFile(0u, path.cstr, 0u, 0u, flags.toUInt(), SAMPLE_RATE.toUInt())
            check(handle != 0u) { "Cannot open MIDI (BASS ${bassError()})" }
            val length = BASS_ChannelGetLength(handle, BASS_POS_BYTE.toUInt())
            val ticks = BASS_ChannelGetLength(handle, BASS_POS_MIDI_TICK.toUInt())
            check(length != POSITION_ERROR && ticks != POSITION_ERROR) { "Cannot read MIDI length (BASS ${bassError()})" }
            durationFrames = (length / 8u).toLong()
            durationTicks = ticks.toLong()
            channels = memScoped {
                val count = alloc<FloatVar>()
                check(BASS_ChannelGetAttribute(handle, BASS_ATTRIB_MIDI_CHANS.toUInt(), count.ptr) != 0)
                count.value.toInt()
            }
            setFont(font)
            check(BASS_MIDI_StreamLoadSamples(handle) != 0) { "Cannot load SoundFont samples (BASS ${bassError()})" }
            check(BASS_MIDI_StreamSetFilter(handle, 1, midiFilter, self.asCPointer()) != 0)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun read(samples: FloatArray, frames: Int): Int {
        val bytes = BASS_ChannelGetData(handle, buffer, (frames * 8).toUInt())
        if (bytes == DATA_ERROR && bassError() == BASS_ERROR_ENDED) return 0
        check(bytes != DATA_ERROR && bytes % 8u == 0u) { "MIDI decode failed (BASS ${bassError()})" }
        val count = (bytes / 4u).toInt()
        for (i in 0 until count) samples[i] = buffer[i]
        return count / 2
    }

    override fun prepare(frame: Long) {
        tail = false
        val target = frame.coerceAtMost((durationFrames - 1).coerceAtLeast(0))
        check(BASS_ChannelSetPosition(handle, (target * 8).toULong(), (BASS_POS_BYTE or BASS_POS_FLUSH).toUInt()) != 0)
    }

    override fun frameAtTick(tick: Long): Long {
        if (tick >= durationTicks) return durationFrames
        tail = false
        check(BASS_ChannelSetPosition(handle, tick.toULong(), BASS_POS_MIDI_TICK.toUInt()) != 0) { "Tick seek $tick failed (BASS ${bassError()})" }
        return (BASS_ChannelGetPosition(handle, BASS_POS_BYTE.toUInt()) / 8u).toLong()
    }

    override fun beginTail() {
        tail = true
        repeat(channels) { channel ->
            // Release pedals as well as keys; never send SOUNDOFF or SYSTEM reset.
            check(BASS_MIDI_StreamEvent(handle, channel.toUInt(), MIDI_EVENT_SUSTAIN.toUInt(), 0u) != 0)
            check(BASS_MIDI_StreamEvent(handle, channel.toUInt(), MIDI_EVENT_SOSTENUTO.toUInt(), 0u) != 0)
            check(BASS_MIDI_StreamEvent(handle, channel.toUInt(), MIDI_EVENT_NOTESOFF.toUInt(), 0u) != 0)
        }
    }

    fun setAttribute(attribute: Int, value: Float) {
        check(BASS_ChannelSetAttribute(handle, attribute.toUInt(), value) != 0)
    }

    fun setFlags(flags: Int, mask: Int) {
        check(BASS_ChannelFlags(handle, flags.toUInt(), mask.toUInt()) != DATA_ERROR)
    }

    fun setFont(font: UInt) = memScoped {
        val entry = alloc<BASS_MIDI_FONT>()
        entry.font = font
        entry.preset = -1
        entry.bank = 0
        check(BASS_MIDI_StreamSetFonts(handle, entry.ptr, 1u) != 0) { "Cannot apply SoundFont (BASS ${bassError()})" }
    }

    fun close() {
        if (handle != 0u) {
            BASS_StreamFree(handle)
            handle = 0u
        }
        if (!disposed) {
            disposed = true
            self.dispose()
            nativeHeap.free(buffer)
        }
    }
}

/** True on devices where BASS is linked; the stub used for non-macOS compile checks returns false. */
internal const val BASS_ENGINE_AVAILABLE = true
