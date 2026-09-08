package jp.project2by2.musicplayer

import kotlin.test.*

class MidiMetadataTest {
    @Test fun readsTitleCopyrightDurationAndCc111WithAndroidPolicy() {
        val bytes = metadataMidi("Piano", "Composer")
        assertEquals(MidiMetadata("Piano", "Composer", 500L, 1000L), parseMidiMetadata(bytes))
    }
    @Test fun malformedInputAndBlankTitleUseFallbacks() {
        assertEquals(MidiMetadata(null, null, null, null), parseMidiMetadata(byteArrayOf(0, 1, 2)))
        assertNull(parseMidiMetadata(metadataMidi("   ", "")).title)
        assertEquals("song.mid", midiDisplayTitle("song.mid", " "))
        assertEquals("Music", midiDisplaySecondaryText("song.mid", "Music", null, null))
        assertEquals("song.mid - Composer", midiDisplaySecondaryText("song.mid", "Music", "Song", "Composer"))
    }
    @Test fun searchMatchesNormalizedMetadataAndRequiresAllTokens() {
        assertTrue(matchesMidiSearch("95090000.MID", "Ｐｉａｎｏ‐曲", "Composer", "Music", "piano composer"))
        assertTrue(matchesMidiSearch("95090000.MID", "曲", null, "Music", "９５０９００００"))
        assertFalse(matchesMidiSearch("song.mid", "Piano", "Composer", "Music", "piano guitar"))
    }
    @Test fun laterTrackTitleCannotOverrideConductorTitle() {
        val first = track(meta(3, "Conductor".encodeToByteArray()) + byteArrayOf(0, -1, 47, 0))
        val second = track(meta(3, "Instrument".encodeToByteArray()) + byteArrayOf(0, -1, 47, 0))
        val header = byteArrayOf(77,84,104,100,0,0,0,6,0,1,0,2,1,-32)
        assertEquals("Conductor", parseMidiMetadata(header + first + second).title)
    }
}

internal fun metadataMidi(title: String, copyright: String): ByteArray = metadataMidi(title.encodeToByteArray(), copyright.encodeToByteArray())
internal fun metadataMidi(title: ByteArray, copyright: ByteArray): ByteArray {
    val events = meta(3, title) + meta(2, copyright) + byteArrayOf(-125,96,-80,111,0,-125,96,-1,47,0)
    return byteArrayOf(77,84,104,100,0,0,0,6,0,0,0,1,1,-32) + track(events)
}
private fun meta(type: Int, bytes: ByteArray) = byteArrayOf(0, -1, type.toByte(), bytes.size.toByte()) + bytes
private fun track(events: ByteArray) = byteArrayOf(77,84,114,107,0,0,(events.size ushr 8).toByte(),events.size.toByte()) + events
