package jp.project2by2.musicplayer.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Platform adapters own their native memory and keep native callbacks alive until close returns. */
interface NativeMidiDecoder : LoopDecoder, AutoCloseable {
    val durationFrames: Long
    val durationTicks: Long
    fun setAttribute(attribute: Int, value: Float)
    fun setFlags(flags: Int, mask: Int)
    fun setFont(font: Int)
}

interface LoopOutputBackend {
    fun create(render: (ByteBuffer, Int) -> Int): Int
    fun lock(handle: Int, locked: Boolean)
    fun positionBytes(handle: Int): Long
    fun reset(handle: Int)
    fun free(handle: Int)
}

/**
 * A stable output handle controls both decoders (pause, volume, focus ducking, fades).
 * Lock order is host monitor -> BASS output lock -> decoder. The render callback NEVER takes
 * the host monitor. Boundary callbacks must not call back into this object's control methods.
 */
class MidiLoopStream(
    private val backend: LoopOutputBackend,
    private val first: NativeMidiDecoder,
    private val second: NativeMidiDecoder,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val renderer: TailLoopRenderer
    private val samples = FloatArray(TailLoopRenderer.BLOCK_FRAMES * 2)
    val durationFrames = first.durationFrames
    val durationTicks = first.durationTicks
    val durationMs get() = durationFrames * 1000 / SAMPLE_RATE
    var output = 0
        private set
    @Volatile var failure: Throwable? = null
        private set

    init {
        try {
            require(first.durationFrames == second.durationFrames)
            renderer = TailLoopRenderer(first, second, durationFrames, SAMPLE_RATE)
            output = backend.create(::render)
            check(output != 0) { "Cannot create loop audio output" }
        } catch (error: Throwable) {
            second.close()
            first.close()
            throw error
        }
    }

    private fun render(buffer: ByteBuffer, length: Int): Int {
        if (failure != null) return Int.MIN_VALUE
        var bytes = 0
        try {
            // This is the consumed position. Prune old loop anchors even without UI polling.
            renderer.songFrame(backend.positionBytes(output).coerceAtLeast(0) / 8)
            buffer.order(ByteOrder.nativeOrder())
            while (bytes < length) {
                val wanted = minOf((length - bytes) / 8, TailLoopRenderer.BLOCK_FRAMES)
                if (wanted == 0) break
                val frames = renderer.render(samples, wanted)
                for (i in 0 until frames * 2) buffer.putFloat(bytes + i * 4, samples[i])
                bytes += frames * 8
                if (frames < wanted || renderer.ended) return bytes or Int.MIN_VALUE
            }
            return bytes
        } catch (error: Throwable) {
            // Never unwind a JVM exception through BASS's native render thread.
            failure = error
            runCatching { onError(error) }
            return bytes or Int.MIN_VALUE
        }
    }

    @Synchronized fun positionMs(): Long = locked {
        renderer.songFrame(backend.positionBytes(output).coerceAtLeast(0) / 8) * 1000 / SAMPLE_RATE
    }

    @Synchronized fun audibleLoopCount(): Long = locked {
        renderer.songFrame(backend.positionBytes(output).coerceAtLeast(0) / 8)
        renderer.audibleLoopCount
    }

    @Synchronized fun seek(ms: Long) = locked {
        renderer.seek(if (ms >= durationMs) durationFrames else ms.coerceAtLeast(0) * SAMPLE_RATE / 1000)
        backend.reset(output) // Discard mixed output too, including all old tail samples.
        failure = null
    }

    @Synchronized fun configure(startMs: Long, endMs: Long, repeat: () -> Boolean) = locked {
        reconfigure {
            renderer.configure(startMs.coerceIn(0, durationMs) * SAMPLE_RATE / 1000,
                if (endMs >= durationMs) durationFrames else endMs.coerceAtLeast(0) * SAMPLE_RATE / 1000, repeat)
        }
    }

    @Synchronized fun configureTicks(start: Long, end: Long, repeat: () -> Boolean) = locked {
        reconfigure { renderer.configureTicks(start.coerceIn(0, durationTicks), end.coerceIn(0, durationTicks), repeat) }
    }

    private inline fun reconfigure(change: () -> Unit) {
        val resume = if (renderer.ended) renderer.songFrame(backend.positionBytes(output).coerceAtLeast(0) / 8) else null
        change()
        // Extending an editor's stopped preview must be playable again without a separate seek.
        if (resume != null) {
            renderer.seek(resume)
            backend.reset(output)
        }
    }

    @Synchronized fun setAttribute(attribute: Int, value: Float) = locked {
        first.setAttribute(attribute, value)
        second.setAttribute(attribute, value)
    }

    @Synchronized fun setFlags(flags: Int, mask: Int) = locked {
        first.setFlags(flags, mask)
        second.setFlags(flags, mask)
    }

    @Synchronized fun setFont(font: Int) = locked {
        first.setFont(font)
        second.setFont(font)
    }

    private inline fun <T> locked(block: () -> T): T {
        check(output != 0) { "MIDI output is closed" }
        backend.lock(output, true)
        try { return block() } finally { backend.lock(output, false) }
    }

    @Synchronized override fun close() {
        if (output == 0) return
        // StreamFree waits for the callback. Do not hold the native output lock here.
        backend.free(output)
        output = 0
        second.close()
        first.close()
    }

    companion object { const val SAMPLE_RATE = 44100 }
}
