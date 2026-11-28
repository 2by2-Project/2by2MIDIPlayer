package jp.project2by2.musicplayer.ios.audio

/** What the output is playing; positions are those being heard, not the decoder's read-ahead. */
data class EnginePosition(val positionMs: Long = 0, val durationMs: Long = 0, val playing: Boolean = false)

/** The section that repeats, in MIDI ticks from the file (CC#111) or in milliseconds (loop editor). */
sealed interface LoopRange {
    data class Ticks(val start: Long, val end: Long?) : LoopRange
    data class Millis(val start: Long, val end: Long) : LoopRange
}

/**
 * BASS/BASSMIDI playback with the same two-synth seamless loop renderer as Android and desktop.
 * Control methods are called from one thread. [LoopBoundary] runs on the audio thread at each loop
 * end and must only read thread-safe state; it returns whether to repeat (false lets the song end).
 */
fun interface LoopBoundary { fun repeat(): Boolean }

interface MidiEngine : AutoCloseable {
    /** Starts the audio device once and returns the library versions. */
    fun initialize(): String
    /** Replaces the active bank; the previous one stays in use if [path] cannot be loaded. */
    fun setSoundFont(path: String)
    val hasSoundFont: Boolean
    fun load(path: String, loop: LoopRange, boundary: LoopBoundary)
    fun configureLoop(loop: LoopRange, boundary: LoopBoundary)
    val loaded: Boolean
    fun play()
    fun pause()
    fun seek(ms: Long)
    fun position(): EnginePosition
    /** Loop passes that have become audible since the last load or seek. */
    fun audibleLoopCount(): Long
    fun synthSettings(voices: Int, effects: Boolean, reverb: Float)
    /** Fades the output to silence over [ms]; [fadeFinished] reports when it has ended. */
    fun startFade(ms: Int)
    fun cancelFade()
    fun fadeFinished(): Boolean
    fun unload()
}
