package jp.project2by2.musicplayer

import com.un4seen.bass.BASS

object BassRuntime {
    private val lock = Any()
    private var refCount = 0
    private var initialized = false

    fun acquire(): Boolean = synchronized(lock) {
        if (!initialized) {
            initialized = BASS.BASS_Init(-1, 44100, 0)
            if (!initialized) return false
        }
        refCount += 1
        true
    }

    fun release() = synchronized(lock) {
        if (refCount > 0) {
            refCount -= 1
        }
        if (initialized && refCount == 0) {
            BASS.BASS_Free()
            initialized = false
        }
    }
}
