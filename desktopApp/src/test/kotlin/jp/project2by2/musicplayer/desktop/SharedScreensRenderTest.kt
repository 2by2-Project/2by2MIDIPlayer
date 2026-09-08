package jp.project2by2.musicplayer.desktop

import androidx.compose.runtime.Composable
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import jp.project2by2.musicplayer.*
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.NowPlayingState
import jp.project2by2.musicplayer.ui.browse.*
import jp.project2by2.musicplayer.ui.playlist.PlaylistScreen
import jp.project2by2.musicplayer.ui.player.NowPlayingSheet
import jp.project2by2.musicplayer.ui.player.PlayerLogo
import jp.project2by2.musicplayer.ui.player.PlayerTopAppBar
import jp.project2by2.musicplayer.ui.player.FileShareDialog
import jp.project2by2.musicplayer.ui.settings.SettingsScreen
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises actual shared screens, including M2 seekbar and M3 insets, without a window/audio device. */
class SharedScreensRenderTest {
    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, content: @Composable () -> Unit) {
        SwingUtilities.invokeAndWait {
            for (width in listOf(360, 1100)) for (dark in listOf(false, true)) {
                val scene = ImageComposeScene(width = width, height = 840) {
                    _2by2MusicPlayerTheme(darkTheme = dark) { Surface { content() } }
                }
                try {
                    scene.render(0).close()
                    scene.render(500_000_000).use { frame ->
                        assertEquals(width, frame.width)
                        val output = File("build/shared-ui-previews/$name-$width-${if (dark) "dark" else "light"}.png")
                        output.parentFile.mkdirs()
                        frame.encodeToData()?.use { output.writeBytes(it.bytes) }
                    }
                } finally { scene.close() }
            }
        }
    }
    @Test fun browseGridAndList() {
        for (mode in FolderViewMode.entries) render("browse-$mode") {
            BrowseScreen(listOf(LibraryFolder("one", "サンプルMIDI楽曲"), LibraryFolder("two", "Music")),
                onFolderClick = {}, onDemoMusicClick = {}, viewMode = mode, onViewModeChange = {})
        }
    }
    @Test fun selectedAndMissingTracks() = render("tracks") {
        TrackList(listOf(LibraryTrack("one", "95090000.MID", "サンプルMIDI楽曲", 242000, loopPointMs = 1200),
            LibraryTrack("two", "95080000.MID", "Music", 0)), MidiListContext.Browse, selectedUri = "one",
            availability = mapOf("two" to MidiFileAvailability.Missing), onItemClick = {}, onAddToPlaylist = {}, onQueueNext = {})
    }
    @Test fun playlists() = render("playlists") {
        PlaylistScreen(listOf(LibraryPlaylist("one", "お気に入り", 12)), {}, {}, {}, {})
    }
    @Test fun logoAndSearchHangBelowToolbar() = render("logo-search") {
        Column {
            PlayerTopAppBar(title = { PlayerLogo() })
            BrowseSearchField(true, "Piano", {})
            Text("Search results")
        }
    }
    @Test fun sharingDestinations() = render("share") {
        FileShareDialog("曲.mid", null, {}, {}, {})
    }
    @Test fun settings() = render("settings") {
        SettingsScreen("Example.sf2", true, 40, true, 1f, true, false,
            {}, {}, onMaxVoicesChange = {}, onEffectsChange = {}, onReverbChange = {}, onLoopChange = {}, onShuffleChange = {})
    }
    @Test fun nowPlaying() = render("now-playing") {
        val data = PianoRollData(listOf(PianoRollNote(60, 0, 2000, 0, 1920, 100, 0, 0)), 8000,
            listOf(0, 2000, 4000, 6000, 8000), listOf(0, 1920, 3840, 5760, 7680), 7680,
            listOf(TickTimeAnchor(0, 0), TickTimeAnchor(7680, 8000)))
        NowPlayingSheet("95090000.MID", "サンプルMIDI楽曲", ui = NowPlayingState(positionMs = 1000, durationMs = 8000),
            pianoRollData = data, loopEnabled = true, shuffleEnabled = false, onLoopChange = {}, onShuffleChange = {},
            onSeekToMs = {}, onPrevious = {}, onNext = {}, onPlayPause = {})
    }
}
