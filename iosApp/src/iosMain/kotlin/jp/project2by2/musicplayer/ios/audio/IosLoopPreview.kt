package jp.project2by2.musicplayer.ios.audio

import jp.project2by2.musicplayer.ui.loop.LoopPreviewControl

/**
 * The loop editor's preview (Android's LoopPreviewPlayer): its own stream and SoundFont handle on the
 * shared BASS device, playing the edited section in a loop. Used from the UI thread after [load].
 */
class IosLoopPreview(private val engine: MidiEngine = BassEngine()) : LoopPreviewControl {
    private var loopStartMs: Long? = null
    private var endMs: Long? = null

    /** False when the preview cannot play (no SoundFont, unreadable MIDI). */
    fun load(midiPath: String, soundFontPath: String, loopStartMs: Long?, endMs: Long?): Boolean = runCatching {
        engine.initialize()
        engine.setSoundFont(soundFontPath)
        this.loopStartMs = loopStartMs
        this.endMs = endMs
        engine.load(midiPath, LoopRange.Ticks(0, null)) { false }
        refreshWindow()
        true
    }.getOrDefault(false)

    override fun setPreviewWindow(loopStartMs: Long?, endMs: Long?) {
        this.loopStartMs = loopStartMs
        this.endMs = endMs
        runCatching { refreshWindow() }
    }

    private fun refreshWindow() {
        val duration = engine.position().durationMs
        val end = (endMs ?: duration).coerceIn(0L, duration)
        val start = loopStartMs?.coerceIn(0L, (end - 1L).coerceAtLeast(0L))
        val repeat = start != null && endMs != null
        engine.configureLoop(LoopRange.Millis(start ?: 0L, end)) { repeat }
    }

    override fun play(): Boolean = runCatching { engine.play(); true }.getOrDefault(false)
    override fun pause() { runCatching { engine.pause() } }
    override fun seekTo(ms: Long) { runCatching { engine.seek(ms) } }
    override fun isPlaying(): Boolean = runCatching { engine.position().playing }.getOrDefault(false)
    override fun currentPositionMs(): Long = runCatching { engine.position().positionMs }.getOrDefault(0L)

    fun release() = engine.close()
}
