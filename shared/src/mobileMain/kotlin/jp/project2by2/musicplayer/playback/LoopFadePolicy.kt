package jp.project2by2.musicplayer.playback

import kotlin.concurrent.Volatile

/** What to do when playback reaches the end of the loop section (or of the song). */
enum class LoopBoundaryAction {
    /** Jump back to the loop start. */
    Repeat,
    /** Let the song end; the host then plays the next song in the queue. */
    End,
    /** Jump back once more and start fading out into the next song. */
    RepeatAndFade,
}

/** Passes through a CC#111 loop before fading out into the next song. */
const val LOOP_REPEAT_BEFORE_FADE_COUNT = 1
const val LOOP_FADE_OUT_DURATION_MS = 8000

/**
 * The smartphone loop rule: with loop enabled (or while editing loop points) the loop repeats
 * forever. Otherwise a song with a CC#111 loop start repeats [repeatsBeforeFade] times and then
 * fades out into the next song, and a song without one simply ends.
 * [onBoundary] runs on the audio thread; hosts [reset] it on load, seek and explicit transport commands.
 */
class LoopFadePolicy(private val repeatsBeforeFade: Int = LOOP_REPEAT_BEFORE_FADE_COUNT) {
    @Volatile private var repeats = 0

    fun onBoundary(loopForever: Boolean, hasLoopStart: Boolean, fadeInProgress: Boolean): LoopBoundaryAction {
        if (loopForever) {
            repeats = 0
            return LoopBoundaryAction.Repeat
        }
        if (!hasLoopStart) return LoopBoundaryAction.End
        // Keep looping underneath the fade.
        if (fadeInProgress) return LoopBoundaryAction.Repeat
        if (repeats < repeatsBeforeFade) {
            repeats += 1
            return LoopBoundaryAction.Repeat
        }
        repeats = 0
        return LoopBoundaryAction.RepeatAndFade
    }

    fun reset() {
        repeats = 0
    }
}
