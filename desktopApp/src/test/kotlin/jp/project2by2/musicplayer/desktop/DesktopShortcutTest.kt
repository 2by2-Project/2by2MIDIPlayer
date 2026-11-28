package jp.project2by2.musicplayer.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class DesktopShortcutTest {
    private fun SemanticsNode.hasEditableText(): Boolean =
        config.contains(SemanticsProperties.EditableText) || children.any { it.hasEditableText() }

    @Test fun ctrlFOpensSearchAndArrowsSkipMeasures() = runBlocking<Unit> {
        val root = Files.createTempDirectory("shortcuts").toFile().canonicalFile
        val demos = File(System.getProperty("midi.demo.dir")).listFiles()!!.filter { it.isFile }.take(3)
        val music = File(root, "music").apply { mkdirs() }
        demos.forEach { it.copyTo(File(music, it.name)) }
        val store = DesktopStore(File(root, "settings.properties")).apply { save(DesktopState(browseDir = music.path)) }
        val controller = DesktopController(store, DesktopMidiFiles(File(System.getProperty("midi.demo.dir"))), BassAudio(device = 0),
            fontCache = File(root, "fonts"), folderIndex = DesktopFolderIndex(home = root, windows = false))
        lateinit var scene: ImageComposeScene
        var time = 0L
        fun frames(count: Int) = repeat(count) { SwingUtilities.invokeAndWait { time += 16_000_000; scene.render(time).close() } }
        fun key(key: Key, ctrl: Boolean = false) {
            SwingUtilities.invokeAndWait { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl)) }
            SwingUtilities.invokeAndWait { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl)) }
            frames(30)
            Thread.sleep(50)
            frames(10)
        }
        fun searchOpen(): Boolean {
            var open = false
            SwingUtilities.invokeAndWait { open = scene.semanticsOwners.any { it.rootSemanticsNode.hasEditableText() } }
            return open
        }
        try {
            withTimeout(30_000) { controller.state.first { it.audioReady || it.error != null } }
            SwingUtilities.invokeAndWait { scene = ImageComposeScene(width = 1100, height = 840) { _2by2MusicPlayerTheme { DesktopApp(controller) } } }
            frames(60)
            // Dismiss the SoundFont recommendation dialog, if shown, outside its bounds.
            SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Press, Offset(5f, 835f), type = PointerType.Mouse, button = PointerButton.Primary) }
            SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Release, Offset(5f, 835f), type = PointerType.Mouse, button = PointerButton.Primary) }
            frames(40)

            assertFalse(searchOpen())
            key(Key.F, ctrl = true)
            assertTrue(searchOpen(), "Ctrl+F opens the search field")
            key(Key.F, ctrl = true)
            assertFalse(searchOpen(), "Ctrl+F again closes it")
            key(Key.F, ctrl = true)
            assertTrue(searchOpen())

            // Arrow keys in the search field stay with the text cursor, not the player.
            controller.setFont(File(root, "test.sf2").apply { writeBytes(testSoundFont()) })
            withTimeout(30_000) { controller.state.first { it.soundFont != null && !it.soundFontLoading } }
            controller.select(File(music, demos.first().name).path, listOf(File(music, demos.first().name).path))
            withTimeout(30_000) { controller.state.first { it.index != null && !it.busy } }
            withTimeout(10_000) { controller.state.first { it.audio.playing } }
            controller.togglePlay(); withTimeout(10_000) { controller.state.first { !it.audio.playing } }
            Thread.sleep(300)
            val before = controller.state.value.audio.positionMs
            key(Key.DirectionRight)
            assertEquals(before, controller.state.value.audio.positionMs, "arrows type into the search field")

            // With search closed, Right/Left move by one measure.
            key(Key.Escape)
            assertFalse(searchOpen())
            controller.seek(0); Thread.sleep(200)
            fun assertNear(expected: Long, message: String) {
                val actual = controller.state.value.audio.positionMs
                assertTrue(kotlin.math.abs(actual - expected) <= 20, "$message: expected ~$expected but was $actual")
            }
            val measures = controller.state.value.index!!.measurePositionsMs
            assertTrue(measures.size > 2, "demo has measures")
            key(Key.DirectionRight)
            assertNear(measures[1], "Right skips to the next measure")
            key(Key.DirectionRight)
            assertNear(measures[2], "Right again")
            key(Key.DirectionLeft)
            assertNear(measures[1], "Left goes back one measure")
        } finally {
            SwingUtilities.invokeAndWait { scene.close() }
            controller.close()
            root.deleteRecursively()
        }
    }

    @Test fun arrowsEnterAndBackspaceBrowseFoldersLikeAnExplorer() = runBlocking<Unit> {
        val root = Files.createTempDirectory("browse-keys").toFile().canonicalFile
        val music = File(root, "music").apply { mkdirs() }
        val alpha = File(music, "Alpha").apply { mkdirs() }
        File(music, "Beta").mkdirs()
        val store = DesktopStore(File(root, "settings.properties")).apply { save(DesktopState(browseDir = music.path)) }
        val controller = DesktopController(store, DesktopMidiFiles(File(System.getProperty("midi.demo.dir"))), BassAudio(device = 0),
            fontCache = File(root, "fonts"), folderIndex = DesktopFolderIndex(home = root, windows = false))
        lateinit var scene: ImageComposeScene
        var time = 0L
        fun frames(count: Int) = repeat(count) { SwingUtilities.invokeAndWait { time += 16_000_000; scene.render(time).close() } }
        fun key(key: Key, ctrl: Boolean = false) {
            SwingUtilities.invokeAndWait { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl)) }
            SwingUtilities.invokeAndWait { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl)) }
            frames(30)
            Thread.sleep(50)
            frames(10)
        }
        fun dir() = controller.state.value.browseDir
        try {
            withTimeout(30_000) { controller.state.first { it.audioReady || it.error != null } }
            SwingUtilities.invokeAndWait { scene = ImageComposeScene(width = 1100, height = 840) { _2by2MusicPlayerTheme { DesktopApp(controller) } } }
            frames(60)
            SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Press, Offset(5f, 835f), type = PointerType.Mouse, button = PointerButton.Primary) }
            SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Release, Offset(5f, 835f), type = PointerType.Mouse, button = PointerButton.Primary) }
            frames(40)

            // Enter without a marked entry does nothing; Down marks the first folder and Enter opens it.
            key(Key.Enter); assertEquals(music.path, dir())
            key(Key.DirectionDown); key(Key.Enter)
            assertEquals(alpha.path, dir())
            // Backspace goes up one folder, like Explorer, and marks the folder just left.
            key(Key.Backspace); assertEquals(music.path, dir())
            key(Key.Enter); assertEquals(alpha.path, dir(), "the folder left is marked, so Enter reopens it")
            key(Key.Backspace); assertEquals(music.path, dir())
            // Alpha is still marked, so Up stays on it and Down moves on to Beta.
            key(Key.DirectionUp); key(Key.DirectionDown); key(Key.Enter)
            assertEquals(File(music, "Beta").path, dir())
            key(Key.Backspace)

            // In search, Backspace belongs to the query and never leaves the folder.
            key(Key.F, ctrl = true)
            key(Key.Backspace); key(Key.Backspace)
            assertEquals(music.path, dir())
        } finally {
            SwingUtilities.invokeAndWait { scene.close() }
            controller.close()
            root.deleteRecursively()
        }
    }
}
