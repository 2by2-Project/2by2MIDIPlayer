package jp.project2by2.musicplayer

data class PianoRollNote(
    val noteNumber: Int,
    val startMs: Long,
    val endMs: Long,
    val startTick: Int,
    val endTick: Int,
    val velocity: Int,
    val channel: Int,
    val trackIndex: Int
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

