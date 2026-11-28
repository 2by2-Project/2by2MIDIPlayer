package jp.project2by2.musicplayer.ios.audio

/**
 * Compile-check stand-in for hosts without Apple's toolchain (Windows/Linux), where the BASS cinterop
 * cannot run. It lets the iOS Kotlin code be type-checked there; app builds on macOS use src/bass.
 */
class BassEngine : MidiEngine {
    private fun unavailable(): Nothing = error("BASS is only available in iOS builds made on macOS")
    override fun initialize(): String = unavailable()
    override fun setSoundFont(path: String) = unavailable()
    override val hasSoundFont: Boolean get() = false
    override fun load(path: String, loop: LoopRange, boundary: LoopBoundary) = unavailable()
    override fun configureLoop(loop: LoopRange, boundary: LoopBoundary) = Unit
    override val loaded: Boolean get() = false
    override fun play() = Unit
    override fun pause() = Unit
    override fun seek(ms: Long) = Unit
    override fun position(): EnginePosition = EnginePosition()
    override fun audibleLoopCount(): Long = 0
    override fun synthSettings(voices: Int, effects: Boolean, reverb: Float) = Unit
    override fun startFade(ms: Int) = Unit
    override fun cancelFade() = Unit
    override fun fadeFinished(): Boolean = false
    override fun unload() = Unit
    override fun close() = Unit
}

internal const val BASS_ENGINE_AVAILABLE = false
