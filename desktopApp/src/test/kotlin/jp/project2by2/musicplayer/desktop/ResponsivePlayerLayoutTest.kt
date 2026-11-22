package jp.project2by2.musicplayer.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import jp.project2by2.musicplayer.ui.player.ResponsivePlayerLayout
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import javax.swing.SwingUtilities
import kotlin.test.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

class ResponsivePlayerLayoutTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun splitterBackgroundFollowsBothMaterialThemes() {
        SwingUtilities.invokeAndWait {
            for (dark in listOf(false, true)) {
                var surface = 0
                val scene = ImageComposeScene(width = 1100, height = 840) {
                    _2by2MusicPlayerTheme(darkTheme = dark) {
                        surface = MaterialTheme.colorScheme.surface.toArgb()
                        ResponsivePlayerLayout(false, false, {}, {}, {}, {},
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface))
                    }
                }
                try {
                    scene.render().use { frame ->
                        val pixels = frame.toComposeImageBitmap().toPixelMap()
                        for (x in 435..445) {
                            if (x == 440) assertNotEquals(surface, pixels[x, 200].toArgb())
                            else assertEquals(surface, pixels[x, 200].toArgb())
                        }
                    }
                } finally { scene.close() }
            }
        }
    }
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun draggingDividerResizesBothPanesAndRetainsWidthWhenSettingsOpen() {
        SwingUtilities.invokeAndWait {
            for (hitOffset in listOf(-3f, 3f)) {
            var left = Rect.Zero
            var right = Rect.Zero
            var settings by mutableStateOf(false)
            val scene = ImageComposeScene(width = 1100, height = 840) {
                ResponsivePlayerLayout(settings, false, {}, library = {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { left = it.boundsInRoot() })
                }, settings = {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { right = it.boundsInRoot() })
                }, player = {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { right = it.boundsInRoot() })
                })
            }
            try {
                scene.render(0).close()
                val initialLeft = left.width
                val initialRight = right.width
                assertEquals(left.right, right.left)
                val x = left.right + hitOffset
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, 300f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Move, Offset(x + 140f, 300f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(x + 140f, 300f), type = PointerType.Mouse)
                scene.render(500_000_000).close()
                assertTrue(left.width > initialLeft + 100f)
                assertTrue(right.width < initialRight - 100f)
                assertEquals(initialLeft + initialRight, left.width + right.width, 0.1f)
                val resized = left.width
                settings = true
                scene.render(1_000_000_000).close()
                assertEquals(resized, left.width)
            } finally { scene.close() }
            }
        }
    }
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun desktopHostRendersSharedLibraryAndSelectedPlayerTogether() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("responsive-player").toFile()
        val controller = DesktopController(DesktopStore(File(directory, "settings.properties")),
            DesktopMidiFiles(File(System.getProperty("midi.demo.dir"))), BassAudio(device = 0))
        try {
            withTimeout(30_000) { controller.state.first { it.audioReady || it.error != null } }.also { assertNull(it.error) }
            val demos = controller.midiFiles.demos()
            controller.select(demos.first(), demos)
            withTimeout(30_000) { controller.state.first { it.current != null && !it.busy } }
            SwingUtilities.invokeAndWait {
                val scene = ImageComposeScene(width = 1100, height = 840) {
                    _2by2MusicPlayerTheme(darkTheme = true) { DesktopApp(controller) }
                }
                try {
                    scene.render(0).close()
                    scene.render(500_000_000).use { frame ->
                        assertEquals(1100, frame.width)
                        val output = File("build/shared-ui-previews/responsive-desktop.png")
                        output.parentFile.mkdirs()
                        frame.encodeToData()?.use { output.writeBytes(it.bytes) }
                    }
                } finally { scene.close() }
            }
        } finally { controller.close(); File(directory, "settings.properties").delete(); directory.delete() }
    }
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun wideSettingsReplaceOnlyRightPaneAndCompactShowsOneScreen() {
        SwingUtilities.invokeAndWait {
            for (width in listOf(360, 599, 600, 701, 841, 1000, 1100, 1600)) {
                var settings by mutableStateOf(false)
                var expandedPlayer by mutableStateOf(false)
                val bounds = mutableMapOf<String, Rect>()
                var libraryCompositions = 0
                val scene = ImageComposeScene(width = width, height = 840) {
                    _2by2MusicPlayerTheme {
                        @Composable fun pane(name: String) {
                            Box(Modifier.fillMaxSize().onGloballyPositioned { bounds[name] = it.boundsInRoot() }) { Text(name) }
                        }
                        ResponsivePlayerLayout(settings, expandedPlayer, {}, library = {
                            remember { libraryCompositions++ }
                            pane("library")
                        }, settings = { pane("settings") }, player = { pane("player") })
                    }
                }
                try {
                    scene.render(0).close()
                    val library = assertNotNull(bounds["library"])
                    if (width >= 600) {
                        val player = assertNotNull(bounds["player"])
                        assertTrue(library.right <= player.left)
                        assertTrue(library.width >= 300f && player.width >= 300f, "$width: $library $player")
                        assertTrue(player.width >= library.width, "$width: $library $player")
                        assertEquals(0f, library.top)
                        assertEquals(840f, player.height)
                        val compositions = libraryCompositions
                        settings = true
                        scene.render(500_000_000).close()
                        assertEquals(player, bounds["settings"])
                        assertEquals(compositions, libraryCompositions, "Opening settings must keep the left screen mounted")
                    } else {
                        assertNull(bounds["player"])
                        assertEquals(width.toFloat(), library.width)
                        expandedPlayer = true
                        scene.render(500_000_000).close()
                        assertEquals(library, bounds["player"])
                        settings = true
                        scene.render(1_000_000_000).close()
                        assertEquals(library, bounds["settings"])
                    }
                } finally { scene.close() }
            }
        }
    }
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun compactHeightKeepsPhoneLandscapeSinglePaneButDesktopWidthStaysWide() {
        SwingUtilities.invokeAndWait {
            for ((size, wide) in listOf((915 to 411) to false, (1000 to 411) to true, (841 to 701) to true, (701 to 841) to true)) {
                val bounds = mutableMapOf<String, Rect>()
                val scene = ImageComposeScene(width = size.first, height = size.second) {
                    ResponsivePlayerLayout(false, false, {}, library = {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { bounds["library"] = it.boundsInRoot() })
                    }, settings = {}, player = {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { bounds["player"] = it.boundsInRoot() })
                    })
                }
                try {
                    scene.render(0).close()
                    assertEquals(wide, bounds["player"] != null, "$size")
                } finally { scene.close() }
            }
        }
    }
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun verticalFoldSplitsPanesAtTheHingeAndOverridesDraggedWidth() {
        SwingUtilities.invokeAndWait {
            var left = Rect.Zero
            var right = Rect.Zero
            var fold by mutableStateOf<Rect?>(null)
            // Book posture on a narrow-for-desktop window: the hinge alone makes it two-pane.
            val scene = ImageComposeScene(width = 860, height = 400) {
                ResponsivePlayerLayout(false, false, {}, library = {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { left = it.boundsInRoot() })
                }, settings = {}, player = {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { right = it.boundsInRoot() })
                }, verticalFoldInWindow = fold)
            }
            try {
                scene.render(0).close()
                assertEquals(Rect.Zero, right, "860x400 without a hinge is compact")
                fold = Rect(420f, 0f, 440f, 400f)
                scene.render(500_000_000).close()
                assertEquals(420f, left.right)
                assertEquals(440f, right.left)
                assertEquals(860f, right.right)
                // A hinge too close to an edge for a pane is ignored.
                fold = Rect(100f, 0f, 100f, 400f)
                scene.render(1_000_000_000).close()
                assertEquals(860f, left.width)
            } finally { scene.close() }
        }
    }
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun touchDragOnCentredHandleResizesBeyondTheThinMouseStrip() {
        SwingUtilities.invokeAndWait {
            var left = Rect.Zero
            val scene = ImageComposeScene(width = 1100, height = 840) {
                ResponsivePlayerLayout(false, false, {}, library = {
                    Box(Modifier.fillMaxSize().onGloballyPositioned { left = it.boundsInRoot() })
                }, settings = {}, player = { Box(Modifier.fillMaxSize()) })
            }
            try {
                scene.render(0).close()
                val initial = left.width
                // 20px right of the divider: outside the 8px strip, inside the 48dp handle target.
                val start = Offset(left.right + 20f, 420f)
                scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Touch)
                for (step in 1..10) scene.sendPointerEvent(PointerEventType.Move, start + Offset(-12f * step, 0f), type = PointerType.Touch)
                scene.sendPointerEvent(PointerEventType.Release, start + Offset(-120f, 0f), type = PointerType.Touch)
                scene.render(500_000_000).close()
                assertTrue(left.width < initial - 80f, "${left.width} vs $initial")
            } finally { scene.close() }
        }
    }
}
