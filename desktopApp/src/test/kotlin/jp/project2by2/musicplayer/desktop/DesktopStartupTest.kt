package jp.project2by2.musicplayer.desktop

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopStartupTest {
    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("desktop-startup").toFile()
        val store = DesktopStore(File(directory, "settings.properties"))
        val cache = File(directory, "cache")
        val library = DesktopMidiFiles(File(System.getProperty("midi.demo.dir")))
        val tracks = library.demos().take(2).mapIndexed { i, demo ->
            File(directory, "日本語の 曲 $i.${if (i == 0) "MID" else "midi"}").apply {
                writeBytes(library.resolve(demo).readBytes())
            }
        }
        val font = File(directory, "音源.dls").apply { writeBytes(testDls(percussion = false)) }
        fun controller(files: List<File> = tracks) = DesktopController(store, library, BassAudio(0), cache, files)
        override fun close() {
            cache.listFiles()?.forEach { it.delete() }; cache.delete()
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }

    private suspend fun DesktopController.await(predicate: (DesktopState) -> Boolean): DesktopState =
        withTimeout(30_000) { state.first(predicate) }

    @Test fun startupWaitsForSavedFontAndPlaysArgumentsAsTemporaryQueue() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val saved = DesktopState(soundFont = fixture.font.canonicalPath,
                playlists = listOf(DesktopPlaylist("saved", "Saved", listOf("demo://sample.mid"))))
            fixture.store.save(saved)
            // Native launchers already separate arguments; spaces and Unicode must stay intact.
            val args = fixture.tracks.map { it.path }.toTypedArray()
            fixture.controller(args.map(::File)).use { player ->
                val started = player.await { it.audio.playing || it.error != null }
                assertNull(started.error)
                assertFalse(started.soundFontLoading)
                assertEquals(fixture.tracks[0].canonicalPath, started.current)
                assertTrue(started.files.isEmpty())
                assertEquals(saved.playlists, started.playlists)
                player.next(1).join()
                assertEquals(fixture.tracks[1].canonicalPath, player.state.value.current)
                player.setVolume(0.4f).join()
                assertTrue(fixture.store.load().files.isEmpty())
                assertEquals(saved.playlists, fixture.store.load().playlists)
            }
        }
    }

    @Test fun firstRunStartsPendingFileAfterUserChoosesFont() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.controller().use { player ->
                val prepared = player.await { it.current != null && !it.busy || it.error != null }
                assertNull(prepared.error)
                assertFalse(prepared.audio.playing)
                assertNull(prepared.soundFont)
                player.setFont(fixture.font)
                val started = player.await { it.audio.playing || it.error != null }
                assertNull(started.error)
                assertEquals(fixture.tracks[0].canonicalPath, started.current)
            }
        }
    }

    @Test fun userSelectionCancelsDeferredStartupPlayback() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.controller().use { player ->
                player.await { it.current != null && !it.busy }
                val selected = fixture.tracks[1].canonicalPath
                player.select(selected, listOf(selected)).join()
                player.setFont(fixture.font)
                assertNull(player.await { it.soundFont != null && !it.soundFontLoading || it.error != null }.error)
                // Drain the serial operation queue after font completion.
                player.setVolume(0.5f).join()
                assertEquals(selected, player.state.value.current)
                assertFalse(player.state.value.audio.playing)
            }
        }
    }

    @Test fun forwardedFilesReplacePlaybackQueueWithoutChangingSavedLibrary() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.store.save(DesktopState(soundFont = fixture.font.canonicalPath))
            fixture.controller().use { player ->
                assertNull(player.await { it.audio.playing || it.error != null }.error)
                player.openLaunchFiles(fixture.tracks.reversed()).join()
                assertEquals(fixture.tracks[1].canonicalPath, player.state.value.current)
                // Read fresh audio position from the device poll.
                assertNull(player.await { it.audio.playing || it.error != null }.error)
                player.next(1).join()
                assertEquals(fixture.tracks[0].canonicalPath, player.state.value.current)
                player.openLaunchFiles(emptyList()).join()
                assertEquals(fixture.tracks[0].canonicalPath, player.state.value.current)
                player.openLaunchFiles(listOf(File(fixture.directory, "missing.mid"))).join()
                assertNotNull(player.state.value.error)
                assertEquals(fixture.tracks[0].canonicalPath, player.state.value.current)
                assertTrue(fixture.store.load().files.isEmpty())
            }
        }
    }

    @Test fun forwardedRequestDuringStartupWaitsForFontAndSupersedesInitialFiles() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.controller().use { player ->
                player.openLaunchFiles(listOf(fixture.tracks[1])).join()
                assertEquals(fixture.tracks[1].canonicalPath, player.state.value.current)
                assertFalse(player.state.value.audio.playing)
                player.setFont(fixture.font)
                val started = player.await { it.audio.playing || it.error != null }
                assertNull(started.error)
                assertEquals(fixture.tracks[1].canonicalPath, started.current)
            }
        }
    }

    @Test fun noArgumentsKeepsNormalStartupAndMissingArgumentReportsError() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.controller(emptyList()).use { player ->
                assertNull(player.await { it.audioReady || it.error != null }.error)
                player.setVolume(0.5f).join()
                assertNull(player.state.value.current)
            }
            val missing = File(fixture.directory, "missing.mid")
            fixture.controller(listOf(missing)).use { player ->
                assertTrue(player.await { it.error != null }.error!!.contains(missing.path))
                assertNull(player.state.value.current)
                assertTrue(player.state.value.files.isEmpty())
            }
        }
    }
}
