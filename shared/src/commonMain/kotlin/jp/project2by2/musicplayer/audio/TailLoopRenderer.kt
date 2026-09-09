package jp.project2by2.musicplayer.audio

/** A stereo float decoder. All methods are called under the output channel's render lock. */
interface LoopDecoder {
    fun read(samples: FloatArray, frames: Int): Int
    /** Clear the previous voices/effects, restore MIDI state, and prepare without playing notes. */
    fun prepare(frame: Long)
    /** Stop accepting file events and release keys/pedals without resetting voices or effects. */
    fun beginTail()
    /** Position a non-playing decoder in MIDI ticks and return its exact PCM frame position. */
    fun frameAtTick(tick: Long): Long
}

/**
 * Two independent synths, one sample clock. No native stream creation/freeing at loop boundaries.
 * The outgoing synth is never sought while its tail is audible. It is recycled only after its
 * bounded release ends. Short loops shorten the tail to half a cycle, so a standby is always ready.
 * Hosts serialize render/control calls and stop/free the output before freeing either decoder.
 */
class TailLoopRenderer(
    private val first: LoopDecoder,
    private val second: LoopDecoder,
    val durationFrames: Long,
    private val sampleRate: Int = 44100,
) {
    companion object { const val BLOCK_FRAMES = 1024 }

    private var active = first
    private var standby = second
    private val dry = FloatArray(BLOCK_FRAMES * 2)
    private val wet = FloatArray(BLOCK_FRAMES * 2)
    private var tailFrames = 0L
    private var tailRemaining = 0L
    private var position = 0L
    private var generated = 0L
    private var loopStart = 0L
    private var boundary = durationFrames
    private var stopped = false
    private var decideRepeat: () -> Boolean = { false }
    private var loops = 0L
    private data class Anchor(val output: Long, val song: Long, val loop: Long)
    private val anchors = ArrayDeque<Anchor>().apply { add(Anchor(0, 0, 0)) }

    init {
        require(durationFrames >= 0 && sampleRate > 0)
        first.prepare(0)
        second.prepare(0)
    }

    fun configure(startFrame: Long, endFrame: Long, repeat: () -> Boolean) {
        loopStart = startFrame.coerceIn(0, durationFrames)
        boundary = endFrame.coerceIn(0, durationFrames)
        decideRepeat = repeat
        // Editing loop points is an explicit transport change; discard a previous tail.
        tailRemaining = 0
        standby.prepare(loopStart)
    }

    fun configureTicks(startTick: Long, endTick: Long, repeat: () -> Boolean) {
        tailRemaining = 0
        val end = standby.frameAtTick(endTick)
        val start = standby.frameAtTick(startTick)
        configure(start, end, repeat)
    }

    /** A manual seek resets both synths and the output-to-song position mapping. */
    fun seek(frame: Long) {
        position = frame.coerceIn(0, durationFrames)
        active.prepare(position)
        standby.prepare(loopStart)
        tailRemaining = 0
        generated = 0
        stopped = false
        anchors.clear()
        loops = 0
        anchors.add(Anchor(0, position, 0))
    }

    /** Use the consumed output position, not the decoder's buffered-ahead position, for UI. */
    fun songFrame(outputFrame: Long): Long {
        val consumed = outputFrame.coerceAtLeast(0)
        // Keep the anchor containing the playhead and all future (already buffered) boundaries.
        while (anchors.size > 1 && anchors[1].output <= consumed) anchors.removeFirst()
        val anchor = anchors.first()
        return (anchor.song + consumed - anchor.output).coerceIn(0, durationFrames)
    }

    val ended: Boolean get() = stopped
    val audibleLoopCount: Long get() = anchors.first().loop

    /** Returns complete stereo frames. A short return indicates end of stream, never starvation. */
    fun render(output: FloatArray, frames: Int): Int {
        require(frames in 0..BLOCK_FRAMES && output.size >= frames * 2)
        if (stopped) return 0
        var written = 0
        while (written < frames) {
            if (position >= boundary) {
                if (boundary <= loopStart || !decideRepeat()) {
                    stopped = true
                    break
                }
                check(tailRemaining == 0L) { "Standby decoder is still releasing" }
                active.beginTail()
                val outgoing = active
                active = standby
                standby = outgoing
                position = loopStart
                anchors.add(Anchor(generated, position, ++loops))
                tailFrames = minOf(sampleRate * 2L, (boundary - loopStart) / 2)
                tailRemaining = tailFrames
                if (tailRemaining == 0L) standby.prepare(loopStart)
            }
            var count = minOf((frames - written).toLong(), boundary - position).toInt()
            if (tailRemaining > 0) count = minOf(count.toLong(), tailRemaining).toInt()
            val read = active.read(dry, count)
            check(read in 0..count) { "Invalid decoder frame count: $read" }
            if (read == 0) { stopped = true; break }
            var tailRead = 0
            if (tailRemaining > 0) tailRead = standby.read(wet, read)
            check(tailRead in 0..read)
            // Natural release first, with a short terminal fade so bounded tails never hard-cut.
            val fadeFrames = minOf(sampleRate / 20L, tailFrames).coerceAtLeast(1)
            for (frame in 0 until read) {
                val gain = if (frame < tailRead) {
                    ((tailRemaining - frame - 1).toFloat() / fadeFrames).coerceIn(0f, 1f)
                } else 0f
                val src = frame * 2
                val dst = (written + frame) * 2
                output[dst] = dry[src] + if (gain > 0) wet[src] * gain else 0f
                output[dst + 1] = dry[src + 1] + if (gain > 0) wet[src + 1] * gain else 0f
            }
            position += read
            generated += read
            written += read
            if (tailRemaining > 0) {
                tailRemaining -= read
                if (tailRemaining == 0L || tailRead < read) {
                    tailRemaining = 0
                    standby.prepare(loopStart)
                }
            }
        }
        return written
    }
}
