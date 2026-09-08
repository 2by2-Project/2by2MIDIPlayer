package jp.project2by2.musicplayer.state

data class NowPlayingState(
    val isPlaying: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0,
    val loopStartMs: Long = 0, val loopEndMs: Long = durationMs
)
