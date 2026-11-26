package jp.project2by2.musicplayer.ui.player

/**
 * Delay assumed when the system cannot report the Bluetooth output latency. On an A2DP (AAC)
 * headset the reported latency was 452 ms, while AudioFlinger measured 512 ms for the real stream.
 */
const val BLUETOOTH_DELAY_FALLBACK_MS = 450

/**
 * Added to the detected Bluetooth delay. The detected value ran below what is actually heard
 * (the stream measured 512 ms against 452 ms detected) and about 100 ms more matched by ear.
 */
const val BLUETOOTH_DELAY_CALIBRATION_MS = 100

/** Range of the user's piano roll timing adjustment (negative shows the roll earlier). */
const val PIANO_ROLL_TIMING_MAX_MS = 300

/**
 * Position the piano roll should show so it matches what the listener hears when the output
 * adds [delayMs] after the engine position.
 *
 * Just after a loop wrap the engine is already back at the loop start while the listener still
 * hears the end of the previous pass, so [afterLoopWrap] keeps the roll on the loop end until
 * the delayed audio catches up instead of jumping early.
 */
fun delayedPianoRollPositionMs(
    positionMs: Long,
    delayMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
    afterLoopWrap: Boolean
): Long {
    val delayed = positionMs - delayMs
    val loopLengthMs = loopEndMs - loopStartMs
    return if (afterLoopWrap && delayMs > 0L && loopLengthMs > 0L && positionMs >= loopStartMs && delayed < loopStartMs) {
        delayed + loopLengthMs
    } else {
        delayed
    }
}
