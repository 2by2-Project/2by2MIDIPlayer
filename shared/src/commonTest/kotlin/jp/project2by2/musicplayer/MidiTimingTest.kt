package jp.project2by2.musicplayer

import kotlin.test.*

class MidiTimingTest {
    @Test fun lastTrackEotIncludesSilentTailAndTempoChanges() {
        val bytes = smf(480,
            events(0, 0xFF, 0x51, 3, 7, 0xA1, 0x20,
                0x83, 0x60, 0xFF, 0x51, 3, 0x0F, 0x42, 0x40,
                0, 0xFF, 0x2F, 0),
            events(0, 0x90, 60, 100, 0x83, 0x60, 0x80, 60, 0,
                0, 0xB0, 111, 0, 0x87, 0x40, 0xFF, 0x2F, 0))
        assertEquals(MidiTiming(1440, 2500, 480, 500), parseMidiTiming(bytes))
        assertEquals(2500L, parseMidiMetadata(bytes).durationMs)
        val index = assertNotNull(parseSmfToPianoRollIndex(bytes))
        assertEquals(1440, index.totalTicks)
        assertEquals(2500L, index.totalDurationMs)
        assertEquals(500L, index.notes.single().endMs)
    }

    @Test fun silentTrackCanDetermineEndAndMissingEotFallsBackToLastEvent() {
        assertEquals(1000L, parseMidiTiming(smf(480,
            events(0x87, 0x40, 0xFF, 0x2F, 0),
            events(0, 0x90, 60, 100, 0x83, 0x60, 0x80, 60, 0))).endMs)
        assertEquals(MidiTiming(480, 500, null, null), parseMidiTiming(smf(480,
            events(0, 0x90, 60, 100, 0x83, 0x60, 0x80, 60, 0))))
    }

    @Test fun tempoChangesDoNotAccumulateMillisecondRoundingError() {
        val tempo = events(1, 0xFF, 0x51, 3, 7, 0xA1, 0x20)
        val track = (0 until 480).fold(byteArrayOf()) { result, _ -> result + tempo } + events(0, 0xFF, 0x2F, 0)
        assertEquals(500L, parseMidiTiming(smf(480, track)).endMs)
    }

    @Test fun smpteIgnoresTempoAndSupportsDropFrameRate() {
        val track = events(0, 0xFF, 0x51, 3, 1, 2, 3, 0x87, 0x68, 0xFF, 0x2F, 0)
        assertEquals(1000L, parseMidiTiming(smf(0xE728, track)).endMs) // 25 fps, 40 ticks/frame
        assertEquals(1001L, parseMidiTiming(smf(0xE328,
            events(0x89, 0x30, 0xFF, 0x2F, 0))).endMs) // 1200 ticks at 30000/1001 fps, 40 ticks/frame
    }

    private fun events(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun smf(division: Int, vararg tracks: ByteArray): ByteArray {
        val header = events(77, 84, 104, 100, 0, 0, 0, 6, 0, if (tracks.size > 1) 1 else 0,
            0, tracks.size, division ushr 8, division and 255)
        return tracks.fold(header) { result, track -> result + events(77, 84, 114, 107,
            track.size ushr 24, track.size ushr 16, track.size ushr 8, track.size) + track }
    }
}
