package jp.project2by2.musicplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.ui.player.ResponsivePlayerLayout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TabletLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resizingKeepsWideSettingsBesideLibraryAndCompactNavigationSinglePane() {
        var width by mutableStateOf(1280)
        var settings by mutableStateOf(false)
        var player by mutableStateOf(false)
        compose.setContent {
            ResponsivePlayerLayout(settings, player, {},
                library = { Box(Modifier.fillMaxSize().testTag("library")) },
                settings = { Box(Modifier.fillMaxSize().testTag("settings")) },
                player = { Box(Modifier.fillMaxSize().testTag("player")) },
                modifier = Modifier.requiredWidth(width.dp))
        }
        for (size in listOf(1280, 1000, 999, 800, 360, 1280)) {
            compose.runOnIdle { width = size; settings = false; player = false }
            compose.onNodeWithTag("library").assertExists()
            if (size >= 1000) {
                val left = compose.onNodeWithTag("library").fetchSemanticsNode().boundsInRoot
                val right = compose.onNodeWithTag("player").fetchSemanticsNode().boundsInRoot
                assertEquals(left.right, right.left, 1f)
                compose.runOnIdle { settings = true }
                compose.onNodeWithTag("library").assertExists()
                compose.onNodeWithTag("player").assertDoesNotExist()
                assertEquals(right, compose.onNodeWithTag("settings").fetchSemanticsNode().boundsInRoot)
            } else {
                compose.onNodeWithTag("player").assertDoesNotExist()
                compose.runOnIdle { player = true }
                compose.onNodeWithTag("library").assertDoesNotExist()
                compose.onNodeWithTag("player").assertExists()
                compose.runOnIdle { settings = true }
                compose.onNodeWithTag("player").assertDoesNotExist()
                compose.onNodeWithTag("settings").assertExists()
            }
        }
    }
}
