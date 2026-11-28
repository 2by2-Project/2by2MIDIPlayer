package jp.project2by2.musicplayer

import kotlin.test.*

class LoopPointMidiTest {
    // 480 ticks per quarter at 120 BPM: one tick is 1/960 s. Two tracks, 4 measures of 4/4.
    private val song = smf(480,
        events(0, 0xFF, 0x58, 4, 4, 2, 24, 8, 0, 0xFF, 0x51, 3, 7, 0xA1, 0x20, 0x8F, 0x00, 0xFF, 0x2F, 0),
        events(0, 0x90, 60, 100, 0x8F, 0x00, 0x80, 60, 0, 0, 0xFF, 0x2F, 0))

    @Test fun savedFileGetsOneLoopStartAndEndsAtTheEndPoint() {
        val rewritten = assertNotNull(buildLoopPointMidi(song, loopTick = 960, endTick = 1440))
        val timing = parseMidiTiming(rewritten)
        assertEquals(960, timing.loopStartTick)
        assertEquals(1440, timing.endTick)
        assertEquals(1500L, timing.endMs)
        // Editing again replaces the loop start, and the tracks keep their length.
        val again = assertNotNull(buildLoopPointMidi(rewritten, loopTick = 480, endTick = 1440))
        assertEquals(MidiTiming(1440, 1500, 480, 500), parseMidiTiming(again))
    }

    @Test fun removingAnOldLoopStartKeepsTheTimingOfLaterEvents() {
        // CC#111 at tick 480 in the note track, before a note at 960.
        val looped = smf(480,
            events(0, 0xFF, 0x51, 3, 7, 0xA1, 0x20, 0, 0xFF, 0x2F, 0),
            events(0x83, 0x60, 0xB0, 111, 0, 0x83, 0x60, 0x90, 60, 100, 0x83, 0x60, 0x80, 60, 0, 0, 0xFF, 0x2F, 0))
        val rewritten = assertNotNull(buildLoopPointMidi(looped, loopTick = 0, endTick = 1440))
        val note = assertNotNull(parseSmfToPianoRollIndex(rewritten)).notes.single()
        assertEquals(960, note.startTick)
        assertEquals(1440, note.endTick)
        assertEquals(0, parseMidiTiming(rewritten).loopStartTick)
    }

    @Test fun initialDataUsesTheLoopStartAndPositionsSnapToQuarterMeasures() {
        val withLoop = assertNotNull(buildLoopPointMidi(song, loopTick = 960, endTick = 1920))
        val initial = loopEditorInitialData(assertNotNull(parseSmfToPianoRollIndex(withLoop)))
        assertEquals(1000L, initial.loopPointMs)
        val data = initial.pianoRollData
        // 1060 ms is 1017.6 ticks; the nearest quarter measure (480-tick steps) is tick 960.
        assertEquals(960, resolveSavedTick(1060, data))
        assertEquals(1000L, snapToMeasureLine(1060, data))
        assertEquals(data.totalTicks, resolveSavedTick(data.totalDurationMs + 5, data))
    }

    @Test fun unreadableDataIsNotRewritten() {
        assertNull(buildLoopPointMidi(byteArrayOf(1, 2, 3), 0, 10))
    }

    private fun events(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun smf(division: Int, vararg tracks: ByteArray): ByteArray {
        val header = events(77, 84, 104, 100, 0, 0, 0, 6, 0, if (tracks.size > 1) 1 else 0,
            0, tracks.size, division ushr 8, division and 255)
        return tracks.fold(header) { result, track -> result + events(77, 84, 114, 107,
            track.size ushr 24, track.size ushr 16, track.size ushr 8, track.size) + track }
    }
}
