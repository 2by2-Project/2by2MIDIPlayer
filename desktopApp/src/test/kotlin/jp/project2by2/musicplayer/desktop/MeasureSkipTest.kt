package jp.project2by2.musicplayer.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeasureSkipTest {
    private val measures = listOf(0L, 2_000L, 4_000L, 6_000L)

    @Test fun nextGoesToTheFollowingMeasureStart() {
        assertEquals(2_000L, measureSkipTarget(measures, 0, 1))
        assertEquals(4_000L, measureSkipTarget(measures, 2_500, 1))
        assertEquals(6_000L, measureSkipTarget(measures, 4_000, 1))
    }

    @Test fun previousFromTheMiddleRestartsThatMeasure() {
        assertEquals(2_000L, measureSkipTarget(measures, 3_900, -1))
        assertEquals(6_000L, measureSkipTarget(measures, 6_500, -1))
    }

    @Test fun previousJustAfterABoundaryGoesToThePrecedingMeasure() {
        assertEquals(2_000L, measureSkipTarget(measures, 4_100, -1))
        assertEquals(4_000L, measureSkipTarget(measures, 6_200, -1))
    }

    @Test fun positionJustBeforeABoundaryCountsAsInsideThatMeasure() {
        // A seek to 4_000 may report 3_999; repeating Next must still advance, Previous must still go back.
        assertEquals(6_000L, measureSkipTarget(measures, 3_999, 1))
        assertEquals(2_000L, measureSkipTarget(measures, 3_999, -1))
    }

    @Test fun edgesAreHandled() {
        assertNull(measureSkipTarget(measures, 6_500, 1))
        assertEquals(0L, measureSkipTarget(measures, 100, -1))
        // Before the first listed measure counts as being in it.
        assertEquals(4_000L, measureSkipTarget(measures.drop(1), 0, 1))
        assertNull(measureSkipTarget(emptyList(), 100, 1))
    }
}
