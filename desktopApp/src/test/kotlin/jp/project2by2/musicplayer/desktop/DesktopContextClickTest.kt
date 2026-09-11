package jp.project2by2.musicplayer.desktop

import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.ui.browse.*
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopContextClickTest {
    private fun SemanticsNode.findText(text: String): SemanticsNode? =
        if (config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) this
        else children.firstNotNullOfOrNull { it.findText(text) }

    private fun ImageComposeScene.click(text: String, button: PointerButton) {
        render().close()
        val node = semanticsOwners.firstNotNullOfOrNull { it.rootSemanticsNode.findText(text) }
        val position = assertNotNull(node).boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, position, type = PointerType.Mouse, button = button)
        sendPointerEvent(PointerEventType.Release, position, type = PointerType.Mouse, button = button)
        render().close()
    }

    @Test fun folderRightClickShowsActionsWithoutNavigationInGridAndList() {
        SwingUtilities.invokeAndWait {
            for (mode in FolderViewMode.entries) {
                var opens = 0
                var actions = 0
                val scene = ImageComposeScene(width = 500, height = 600) {
                    _2by2MusicPlayerTheme { Surface {
                        BrowseScreen(listOf(LibraryFolder("/music", "Music")), onFolderClick = { opens++ },
                            onDemoMusicClick = null, viewMode = mode, onViewModeChange = {}, onFolderActions = { actions++ })
                    } }
                }
                try {
                    scene.click("Music", PointerButton.Secondary)
                    assertEquals(1, actions)
                    assertEquals(0, opens)
                    scene.click("Music", PointerButton.Primary)
                    assertEquals(1, opens)
                    assertEquals(1, actions)
                } finally { scene.close() }
            }
        }
    }

    @Test fun trackRightClickOpensExistingActionsWithoutPlayback() {
        SwingUtilities.invokeAndWait {
            var plays = 0
            var actionsShown = false
            val scene = ImageComposeScene(width = 500, height = 600) {
                _2by2MusicPlayerTheme { Surface {
                    TrackList(listOf(LibraryTrack("/music/song.mid", "Song", "Music", 10000)),
                        MidiListContext.Browse, selectedUri = null, onItemClick = { plays++ },
                        onAddToPlaylist = {}, onQueueNext = {}, actions = { _, _ -> actionsShown = true })
                } }
            }
            try {
                scene.click("Song", PointerButton.Secondary)
                assertTrue(actionsShown)
                assertEquals(0, plays)
            } finally { scene.close() }
        }
    }
}
