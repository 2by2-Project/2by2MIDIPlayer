package jp.project2by2.musicplayer.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopDlsImportTest {
    @Test fun importsPreservesOldFontOnFailureAndRegeneratesOnRestart() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("dls-controller-").toFile()
        val cache = File(dir, "cache")
        val source = File(dir, "音源.dls").apply { writeBytes(testDls(percussion = false)) }
        val invalid = File(dir, "invalid.dls").apply { writeText("invalid") }
        val store = DesktopStore(File(dir, "settings.properties"))
        fun controller() = DesktopController(store = store, engine = BassAudio(0), fontCache = cache)
        suspend fun DesktopController.await(predicate: (DesktopState) -> Boolean) =
            withTimeout(30_000) { state.first(predicate) }
        try {
            controller().use { player ->
                assertNull(player.await { it.audioReady || it.error != null }.error)
                player.setFont(source)
                assertNull(player.await { it.soundFont != null && !it.soundFontLoading || it.error != null }.error)
                assertEquals(source.canonicalPath, store.load().soundFont)
                assertEquals(1, cache.listFiles()!!.size)
                val converted = cache.listFiles()!!.single()
                player.setFont(invalid)
                player.await { it.error != null && !it.soundFontLoading }
                assertEquals(source.canonicalPath, player.state.value.soundFont)
                assertEquals(source.canonicalPath, store.load().soundFont)
                assertTrue(converted.exists(), "Failed import must retain the active font file")
            }
            assertTrue(cache.listFiles()!!.isEmpty(), "Close must free BASS before deleting its temporary font")
            controller().use { player ->
                assertNull(player.await { it.error != null || it.audioReady && !it.soundFontLoading && cache.listFiles()!!.isNotEmpty() }.error)
                assertEquals(source.canonicalPath, player.state.value.soundFont)
            }
            assertTrue(cache.listFiles()!!.isEmpty())
        } finally {
            cache.listFiles()?.forEach { it.delete() }; cache.delete()
            dir.listFiles()?.forEach { it.delete() }; dir.delete()
        }
    }
}
