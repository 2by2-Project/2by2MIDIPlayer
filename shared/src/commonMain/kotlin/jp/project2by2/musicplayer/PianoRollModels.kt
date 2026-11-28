package jp.project2by2.musicplayer

data class PianoRollNote(
    val noteNumber: Int,
    val startMs: Long,
    val endMs: Long,
    val startTick: Int,
    val endTick: Int,
    val velocity: Int,
    val channel: Int,
    val trackIndex: Int,
    /** Channel volume (controller 7) when the note starts; 100 is the General MIDI default. */
    val volume: Int = 100,
    /** Channel expression (controller 11) when the note starts; 127 is the default. */
    val expression: Int = 127
)

data class TickTimeAnchor(
    val tick: Int,
    val ms: Long
)

data class TimeSignature(
    val tick: Int,
    val numerator: Int,
    val denominator: Int
)

data class PianoRollData(
    val notes: List<PianoRollNote>,
    val totalDurationMs: Long,
    val measurePositions: List<Long>,
    val measureTickPositions: List<Int>,
    val totalTicks: Int,
    val tickTimeAnchors: List<TickTimeAnchor>
)

