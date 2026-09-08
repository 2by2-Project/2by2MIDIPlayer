package jp.project2by2.musicplayer.desktop

import java.nio.file.Files
import kotlin.test.*

class DesktopTest {
    @Test fun viewportIncludesSustainedNotesAndExcludesExpiredNotes() {
        fun note(start: Long, end: Long) = jp.project2by2.musicplayer.PianoRollNote(60, start, end, start.toInt(), end.toInt(), 100, 0, 0)
        val sustained = note(0, 10000)
        val current = note(5500, 6000)
        val viewport = NoteViewport(listOf(sustained, note(1000, 2000), current, note(9000, 9500)))
        assertEquals(listOf(sustained, current), viewport.visible(5000, 7000))
        assertTrue(viewport.visible(11000, 12000).isEmpty())
        assertTrue(NoteViewport(emptyList()).visible(0, 100).isEmpty())
    }
    @Test fun platformSelectionRejectsWrongArchitecture() {
        assertEquals(NativePlatform.Windows, NativePlatform.detect("Windows 11", "amd64"))
        assertEquals(NativePlatform.Linux, NativePlatform.detect("Linux", "x86_64"))
        assertFailsWith<IllegalArgumentException> { NativePlatform.detect("Linux", "aarch64") }
        assertFailsWith<IllegalStateException> { NativePlatform.detect("Mac OS X", "x86_64") }
    }
    @Test fun settingsAndPlaylistsSurviveRestartWithUnicodePaths() {
        val file = Files.createTempDirectory("desktop-store-test").resolve("settings.properties").toFile()
        try {
            val store = DesktopStore(file)
            val state = DesktopState(files = listOf("C:/曲/風.mid", "/music/song.mid"),
                playlists = listOf(DesktopPlaylist("a", "作業用 ♪", listOf("C:/曲/風.mid"))),
                soundFont = "C:/音源/piano.sf2", volume = 0.4f, loop = true, shuffle = true,
                maxVoices = 200, effectsEnabled = true, reverbStrength = 2.5f)
            store.save(state)
            assertEquals(state, store.load())
            store.save(state.copy(files = emptyList()))
            assertTrue(store.load().files.isEmpty())
        } finally { file.delete(); file.parentFile.delete() }
    }
    @Test fun invalidSavedVolumeCannotReachNativeAudio() {
        val file = Files.createTempFile("desktop-volume", ".properties").toFile()
        try {
            file.writeText("volume=NaN")
            assertEquals(0.7f, DesktopStore(file).load().volume)
        } finally { file.delete() }
    }
    @Test fun timeFormattingIsStable() {
        assertEquals("0:00", formatTime(-1))
        assertEquals("1:05", formatTime(65000))
        assertEquals("120:00", formatTime(7200000))
    }
}
