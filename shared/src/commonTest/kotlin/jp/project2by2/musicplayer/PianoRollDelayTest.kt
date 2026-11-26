package jp.project2by2.musicplayer

import jp.project2by2.musicplayer.ui.player.delayedPianoRollPositionMs
import kotlin.test.Test
import kotlin.test.assertEquals

class PianoRollDelayTest {
    @Test fun withoutDelayPositionIsUnchanged() {
        assertEquals(5_000L, delayedPianoRollPositionMs(5_000, 0, 1_000, 9_000, false))
    }

    @Test fun delayShowsEarlierPosition() {
        assertEquals(4_750L, delayedPianoRollPositionMs(5_000, 250, 1_000, 9_000, false))
    }

    @Test fun afterLoopWrapRollStaysOnTheEndOfThePreviousPass() {
        // Engine wrapped 100 ms ago: the listener still hears 150 ms before the loop end.
        assertEquals(8_850L, delayedPianoRollPositionMs(1_100, 250, 1_000, 9_000, true))
    }

    @Test fun wrapCorrectionEndsOnceTheDelayHasElapsed() {
        assertEquals(1_000L, delayedPianoRollPositionMs(1_250, 250, 1_000, 9_000, true))
        assertEquals(1_050L, delayedPianoRollPositionMs(1_300, 250, 1_000, 9_000, true))
    }

    @Test fun firstPassThroughLoopStartIsNotTreatedAsWrap() {
        assertEquals(850L, delayedPianoRollPositionMs(1_100, 250, 1_000, 9_000, false))
    }

    @Test fun seekBeforeLoopStartIsNeverShifted() {
        assertEquals(-50L, delayedPianoRollPositionMs(200, 250, 1_000, 9_000, true))
    }
}
