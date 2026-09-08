package jp.project2by2.musicplayer.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import jp.project2by2.musicplayer.MiniPlayerContent
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopComposeSmokeTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun materialControlsAndSharedPlayerRenderWithDesktopRuntime() {
        // Render without launching a window or touching the user's settings/audio device.
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width = 960, height = 640) {
                _2by2MusicPlayerTheme(darkTheme = true) {
                    Column {
                        OutlinedTextField(value = "", onValueChange = {}, label = { Text("曲名を検索") })
                        Slider(value = 0.5f, onValueChange = {})
                        MiniPlayerContent("MIDI", null, null, false, 0.5f, "0:30", {}, {})
                        NavigationBar {
                            NavigationBarItem(selected = true, onClick = {}, icon = { Text("♪") }, label = { Text("ブラウズ") })
                        }
                    }
                }
            }
            try {
                scene.render().use { frame -> assertEquals(960, frame.width) }
            } finally { scene.close() }
        }
    }
}
