package jp.project2by2.musicplayer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Run on Pixel Tablet in landscape (1280dp available width). */
class TabletHostTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsOpenInRightPaneAndBackReturnsToPlayer() {
        val settings = compose.activity.getString(R.string.settings)
        val back = compose.activity.getString(R.string.back)
        val category = compose.activity.getString(R.string.settings_category_midi_synthesizer)
        compose.onNodeWithContentDescription(settings).performClick()
        val libraryButton = compose.onNodeWithContentDescription(settings).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val settingsCategory = compose.onNodeWithText(category).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(settingsCategory.left > libraryButton.right)
        compose.onNodeWithContentDescription(back).performClick()
        compose.onNodeWithText(category).assertDoesNotExist()
        compose.onNodeWithContentDescription(settings).assertIsDisplayed()
    }
}
