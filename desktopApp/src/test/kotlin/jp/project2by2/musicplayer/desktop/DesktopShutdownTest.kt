package jp.project2by2.musicplayer.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertNull

class DesktopShutdownTest {
    @Test fun closeAfterInitializationAndRepeatedDisposal() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("desktop-shutdown").toFile()
        val controller = DesktopController(
            store = DesktopStore(File(directory, "settings.properties")),
            engine = BassAudio(device = 0),
        )
        try {
            val ready = withTimeout(30_000) { controller.state.first { it.audioReady || it.error != null } }
            assertNull(ready.error)
            controller.close() // Window close request.
            controller.close() // Composition disposal.
        } finally {
            controller.close()
            directory.delete()
        }
    }

    @Test fun closeImmediatelyWhileInitializationMayBeQueued() {
        val directory = Files.createTempDirectory("desktop-shutdown-early").toFile()
        try {
            val controller = DesktopController(
                store = DesktopStore(File(directory, "settings.properties")),
                engine = BassAudio(device = 0),
            )
            controller.close()
            controller.close()
        } finally {
            directory.delete()
        }
    }
}
