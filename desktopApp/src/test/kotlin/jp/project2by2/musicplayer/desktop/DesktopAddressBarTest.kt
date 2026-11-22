package jp.project2by2.musicplayer.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
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

@OptIn(ExperimentalComposeUiApi::class)
class DesktopAddressBarTest {
    private fun SemanticsNode.findAll(text: String): List<SemanticsNode> =
        (if (config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) listOf(this) else emptyList()) +
            children.flatMap { it.findAll(text) }

    @Test fun addressBarOpensAncestorsRepeatedly() = runBlocking<Unit> {
        val root = Files.createTempDirectory("address-bar").toFile().canonicalFile
        val deep = File(root, "musics/MIDI/Moon☆Wind").apply { mkdirs() }
        File(root, "musics/MIDI/Raziel").mkdirs()
        // A folder longer than the window, with subfolders listed ahead of its tracks.
        val many = File(root, "musics/MIDI/Many")
        (0 until 40).forEach { File(many, "f%02d".format(it)).mkdirs() }
        File(System.getProperty("midi.demo.dir")).listFiles()!!.filter { it.isFile }.take(8).forEach { it.copyTo(File(many, it.name)) }
        val store = DesktopStore(File(root, "settings.properties")).apply { save(DesktopState(browseDir = deep.parent)) }
        val controller = DesktopController(store, DesktopMidiFiles(File(System.getProperty("midi.demo.dir"))), BassAudio(device = 0),
            fontCache = File(root, "fonts"), folderIndex = DesktopFolderIndex(home = root, windows = false))
        lateinit var scene: ImageComposeScene
        var time = 0L
        fun frames(count: Int) = repeat(count) { SwingUtilities.invokeAndWait { time += 16_000_000; scene.render(time).close() } }
        fun press(position: Offset) {
            SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Press, position, type = PointerType.Mouse, button = PointerButton.Primary) }
            SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Release, position, type = PointerType.Mouse, button = PointerButton.Primary) }
            frames(30)
            Thread.sleep(50)
            frames(10)
        }
        /** Clicks the topmost node showing [text]: the address bar pill rather than a folder of the same name. */
        fun click(text: String, pill: Boolean = true) {
            var nodes = emptyList<SemanticsNode>()
            SwingUtilities.invokeAndWait { nodes = scene.semanticsOwners.flatMap { it.rootSemanticsNode.findAll(text) } }
            val node = assertNotNull(if (pill) nodes.minByOrNull { it.boundsInRoot.top } else nodes.maxByOrNull { it.boundsInRoot.top }, text)
            press(node.boundsInRoot.center)
        }
        try {
            withTimeout(30_000) { controller.state.first { it.audioReady || it.error != null } }
            SwingUtilities.invokeAndWait { scene = ImageComposeScene(width = 1100, height = 840) { _2by2MusicPlayerTheme { DesktopApp(controller) } } }
            frames(60)
            // Without a SoundFont the recommendation dialog opens first; dismiss it outside its bounds.
            press(Offset(5f, 835f))
            fun dir() = controller.state.value.browseDir

            click("Moon☆Wind", pill = false); assertEquals(deep.path, dir())
            // Ancestors stay clickable after each navigation, including the folder the app started in.
            click("MIDI"); assertEquals(File(root, "musics/MIDI").path, dir())
            click("musics"); assertEquals(File(root, "musics").path, dir())
            click("MIDI", pill = false); assertEquals(File(root, "musics/MIDI").path, dir())
            click("Moon☆Wind", pill = false); assertEquals(deep.path, dir())
            click("musics"); assertEquals(File(root, "musics").path, dir())

            // Every folder opens at the top, whether entered or returned to.
            fun visible(text: String): Boolean {
                var found = false
                SwingUtilities.invokeAndWait { found = scene.semanticsOwners.any { it.rootSemanticsNode.findAll(text).isNotEmpty() } }
                return found
            }
            click("MIDI", pill = false)
            click("Many", pill = false); assertEquals(many.path, dir())
            assertTrue(visible("f00"), "a folder opens scrolled to its first entry")
            click("MIDI"); click("Many", pill = false)
            assertTrue(visible("f00"), "a folder opened again starts at the top")
        } finally {
            SwingUtilities.invokeAndWait { scene.close() }
            controller.close()
            root.deleteRecursively()
        }
    }
}
