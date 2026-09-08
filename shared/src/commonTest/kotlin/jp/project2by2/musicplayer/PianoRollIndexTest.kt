package jp.project2by2.musicplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PianoRollIndexTest {
    @Test
    fun tempoChangeAndLoopPointKeepTheirMusicalPositions() {
        val index = assertNotNull(parseSmfToPianoRollIndex(smf(
            0x00, 0x90, 60, 100,              // Note on at tick 0
            0x83, 0x60, 0xB0, 111, 0,        // Loop at tick 480
            0x00, 0xFF, 0x51, 3, 0x0F, 0x42, 0x40, // 60 BPM
            0x83, 0x60, 0x80, 60, 0,         // Note off at tick 960
            0x00, 0xFF, 0x2F, 0
        )))
        assertEquals(960, index.totalTicks)
        assertEquals(1500L, index.totalDurationMs)
        assertEquals(480, index.loopPointTick)
        assertEquals(listOf(TickTimeAnchor(0, 0), TickTimeAnchor(480, 500), TickTimeAnchor(960, 1500)), index.tickTimeAnchors)
        assertEquals(PianoRollNote(60, 0, 1500, 0, 960, 100, 0, 0), index.notes.single())
        assertEquals(500L, tickToMsFast(480, index.tickTimeAnchors))
        assertEquals(720, msToTick(1000, index.tickTimeAnchors, index.totalTicks))
    }

    @Test
    fun runningStatusAndVelocityZeroStillEndNotes() {
        val index = assertNotNull(parseSmfToPianoRollIndex(smf(
            0, 0x92, 64, 90,
            0x83, 0x60, 64, 0,
            0, 0xFF, 0x2F, 0
        )))
        assertEquals(PianoRollNote(64, 0, 500, 0, 480, 90, 2, 0), index.notes.single())
        assertNull(index.loopPointTick)
    }

    @Test
    fun timeSignatureControlsMeasureBoundaries() {
        val index = assertNotNull(parseSmfToPianoRollIndex(smf(
            0, 0xFF, 0x58, 4, 3, 2, 24, 8,
            0, 0x90, 60, 100,
            0x96, 0x40, 0x80, 60, 0, // 2880 ticks = two 3/4 measures
            0, 0xFF, 0x2F, 0
        )))
        assertEquals(listOf(0, 1440), index.measureTickPositions)
        assertEquals(listOf(0L, 1500L), index.measurePositionsMs)
    }

    @Test
    fun timingClampsAtBoundariesAndAcceptsEmptyAnchors() {
        val anchors = listOf(TickTimeAnchor(0, 0), TickTimeAnchor(480, 500))
        assertEquals(0L, tickToMsFast(-10, anchors))
        assertEquals(500L, tickToMsFast(1000, anchors))
        assertEquals(0, msToTick(-10, anchors, 480))
        assertEquals(480, msToTick(1000, anchors, 480))
        assertEquals(0L, tickToMsFast(100, emptyList()))
        assertEquals(0, msToTick(100, emptyList(), 0))
        assertNull(parseSmfToPianoRollIndex(byteArrayOf()))
        assertNull(parseSmfToPianoRollIndex(ByteArray(14)))
    }

    private fun smf(vararg events: Int): ByteArray {
        val header = byteArrayOf(77, 84, 104, 100, 0, 0, 0, 6, 0, 0, 0, 1, 1, 0xE0.toByte())
        val track = byteArrayOf(77, 84, 114, 107, 0, 0, (events.size ushr 8).toByte(), events.size.toByte())
        return header + track + events.map { it.toByte() }.toByteArray()
    }
}
