package jp.project2by2.musicplayer

import jp.project2by2.musicplayer.ui.player.glossStrengthOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteGlossTest {
    @Test fun glossGrowsWithTheVelocity() {
        assertTrue(glossStrengthOf(40) < glossStrengthOf(80))
        assertTrue(glossStrengthOf(80) < glossStrengthOf(120))
    }

    @Test fun glossIsProportionalToTheVelocity() {
        assertEquals(64f / 127f, glossStrengthOf(64), 1e-6f)
        assertEquals(1f, glossStrengthOf(127))
    }

    @Test fun glossStaysInRange() {
        assertEquals(0f, glossStrengthOf(0))
        assertEquals(1f, glossStrengthOf(300))
        assertEquals(0f, glossStrengthOf(-5))
    }

    @Test fun aQuieterChannelIsDimmerInProportion() {
        assertEquals(glossStrengthOf(100) / 2f, glossStrengthOf(100, volume = 50), 1e-6f)
        assertEquals(0f, glossStrengthOf(100, volume = 0))
    }

    @Test fun volumeAboveTheDefaultIsNotBrighter() {
        assertEquals(glossStrengthOf(100, volume = 100), glossStrengthOf(100, volume = 127))
    }

    @Test fun expressionScalesTheGlossToo() {
        assertEquals(glossStrengthOf(100) * 64f / 127f, glossStrengthOf(100, expression = 64), 1e-6f)
    }
}
