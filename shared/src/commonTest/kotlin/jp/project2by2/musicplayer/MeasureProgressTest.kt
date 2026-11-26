package jp.project2by2.musicplayer

import jp.project2by2.musicplayer.ui.player.currentMeasureProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeasureProgressTest {
    private val measures = listOf(0, 480, 960)

    @Test fun flashIsFullOnEnteringAMeasure() {
        val p = currentMeasureProgress(480, measures, 1_440)!!
        assertEquals(1, p.index)
        assertEquals(1f, p.flash)
    }

    @Test fun flashFadesLinearlyTowardTheNextMeasure() {
        assertEquals(0.5f, currentMeasureProgress(240, measures, 1_440)!!.flash)
        assertEquals(0.25f, currentMeasureProgress(360, measures, 1_440)!!.flash, 1e-6f)
    }

    @Test fun lastMeasureEndsAtTheGivenLastTick() {
        val p = currentMeasureProgress(1_200, measures, 1_440)!!
        assertEquals(2, p.index)
        assertEquals(1_440, p.endTick)
        assertEquals(0.5f, p.flash)
    }

    @Test fun nothingBeforeTheFirstMeasure() {
        assertNull(currentMeasureProgress(10, listOf(100, 580), 1_000))
        assertNull(currentMeasureProgress(0, emptyList(), 1_000))
    }

    @Test fun degenerateLastMeasureHasNoFlash() {
        assertEquals(0f, currentMeasureProgress(960, measures, 960)!!.flash)
    }
}
