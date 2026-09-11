package jp.project2by2.musicplayer.desktop

import jp.project2by2.musicplayer.parseMidiMetadata
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopParityTest {
    @Test fun openingFilesPlaysImmediatelyWithoutChangingSavedLibrary() = runBlocking<Unit> {
        val temp = Files.createTempDirectory("open-midi-controller").toFile()
        val font = File(temp, "test.dls").apply { writeBytes(testDls(percussion = false)) }
        val store = DesktopStore(File(temp, "settings.properties"))
        val library = DesktopMidiFiles(File(System.getProperty("midi.demo.dir")))
        val files = library.demos().take(3).map(library::resolve)
        val initial = DesktopState(files = listOf(files[0].canonicalPath),
            playlists = listOf(DesktopPlaylist("saved", "Saved", listOf(files[0].canonicalPath))))
        store.save(initial)
        val cache = File(temp, "cache")
        try {
            DesktopController(store, library, BassAudio(device = 0), cache).use { player ->
                suspend fun awaitState(predicate: (DesktopState) -> Boolean) =
                    withTimeout(30_000) { player.state.first(predicate) }
                assertNull(awaitState { it.audioReady || it.error != null }.error)
                player.setFont(font)
                assertNull(awaitState { it.soundFont != null && !it.soundFontLoading || it.error != null }.error)
                player.select(files[0].canonicalPath, listOf(files[0].canonicalPath)).join()
                player.openFiles(files.drop(1)).join()
                val opened = awaitState { it.audio.playing || it.error != null }
                assertNull(opened.error)
                assertEquals(files[1].canonicalPath, opened.current)
                assertEquals(initial.files, opened.files)
                assertEquals(initial.playlists, opened.playlists)
                player.next(1).join()
                assertEquals(files[2].canonicalPath, player.state.value.current)
                player.openFiles(emptyList()).join()
                assertEquals(files[2].canonicalPath, player.state.value.current)
                // A later settings save must not persist the temporary queue as library entries.
                player.setVolume(0.5f).join()
                assertEquals(initial.files, store.load().files)
                assertEquals(initial.playlists, store.load().playlists)
            }
        } finally {
            cache.listFiles()?.forEach { it.delete() }
            cache.delete()
            temp.listFiles()?.forEach { it.delete() }
            temp.delete()
        }
    }

    @Test fun demoCatalogCanOpenAndPersistInPlaylistWithoutAudioOutput() = runBlocking<Unit> {
        val temp = Files.createTempDirectory("demo-controller").toFile()
        val library = DesktopMidiFiles(File(System.getProperty("midi.demo.dir")))
        val store = DesktopStore(File(temp, "settings.properties"))
        val controller = DesktopController(store, library, BassAudio(device = 0))
        suspend fun awaitState(predicate: (DesktopState) -> Boolean) = withTimeout(30_000) { controller.state.first(predicate) }
        try {
            awaitState { it.audioReady || it.error != null }.also { assertNull(it.error) }
            controller.loadDemos()
            val loaded = awaitState { it.demoFiles.isNotEmpty() && it.metadata.size >= it.demoFiles.size }
            assertEquals(150, loaded.demoFiles.size)
            val id = loaded.demoFiles.first()
            assertEquals(parseMidiMetadata(library.resolve(id).readBytes()), loaded.metadata[id])
            controller.select(id, loaded.demoFiles)
            val selected = awaitState { it.current == id && !it.busy }
            assertTrue(assertNotNull(selected.index).notes.isNotEmpty())
            assertFalse(selected.audio.playing)
            controller.createPlaylist("Samples")
            val playlist = awaitState { it.playlists.size == 1 }.playlists.single()
            controller.addToPlaylist(id, playlist.id)
            awaitState { id in it.playlists.single().paths && !it.busy }
        } finally { controller.close() }
        try {
            assertTrue(DesktopMidiFiles.isDemo(store.load().playlists.single().paths.single()))
            assertFailsWith<IllegalArgumentException> { library.resolve("demo://../secret.mid") }
        } finally { File(temp, "settings.properties").delete(); temp.delete() }
    }

    @Test fun exportIsExactAndDoesNotOverwriteWithoutApproval() {
        val temp = Files.createTempDirectory("midi-share").toFile()
        val source = File(temp, "曲.mid").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val target = File(temp, "書き出し.mid")
        try {
            exportMidiFile(source, target, false)
            assertContentEquals(source.readBytes(), target.readBytes())
            source.writeBytes(byteArrayOf(4, 5))
            assertFailsWith<FileAlreadyExistsException> { exportMidiFile(source, target, false) }
            assertContentEquals(byteArrayOf(1, 2, 3), target.readBytes())
            exportMidiFile(source, target, true)
            assertContentEquals(source.readBytes(), target.readBytes())
            assertFailsWith<IllegalArgumentException> { exportMidiFile(source, source, true) }
            val transfer = MidiFileTransfer(source)
            assertEquals(listOf(source), transfer.getTransferData(DataFlavor.javaFileListFlavor))
            assertFalse(transfer.isDataFlavorSupported(DataFlavor.stringFlavor))
        } finally { source.delete(); target.delete(); temp.delete() }
    }

    @Test fun metadataCacheSeparatesEqualFilenamesAndRefreshesChangedFiles() {
        val temp = Files.createTempDirectory("midi-metadata").toFile()
        val a = File(temp, "a").apply { mkdir() }
        val b = File(temp, "b").apply { mkdir() }
        val source = DesktopMidiFiles(File(System.getProperty("midi.demo.dir")))
        val demos = source.demos()
        val first = File(a, "same.mid").apply { writeBytes(source.resolve(demos.first()).readBytes()) }
        val second = File(b, "same.mid").apply { writeBytes(source.resolve(demos.last()).readBytes()) }
        try {
            val reader = DesktopMetadataReader()
            assertEquals(parseMidiMetadata(first.readBytes()), reader.read(first))
            assertEquals(parseMidiMetadata(second.readBytes()), reader.read(second))
            first.writeBytes(second.readBytes())
            first.setLastModified(first.lastModified() + 2000)
            assertEquals(parseMidiMetadata(second.readBytes()), reader.read(first))
        } finally { first.delete(); second.delete(); a.delete(); b.delete(); temp.delete() }
    }
}
