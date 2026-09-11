package jp.project2by2.musicplayer.desktop

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopFileInteractionTest {
    @Test fun fileTransferAndLinuxUriListAcceptOnlyLocalFiles() {
        val file = File(System.getProperty("java.io.tmpdir"), "曲 name.MID")
        assertEquals(listOf(file), droppedFiles(MidiFileTransfer(file)))
        val flavor = DataFlavor("text/uri-list;class=java.lang.String")
        val transfer = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(flavor)
            override fun isDataFlavorSupported(value: DataFlavor) = value == flavor
            override fun getTransferData(value: DataFlavor): Any =
                "# comment\r\n${file.toURI()}\r\nhttps://example.com/song.mid\r\ninvalid uri"
        }
        assertEquals(listOf(file), droppedFiles(transfer))
        assertTrue(droppedFiles(StringSelection("https://example.com/song.mid")).isEmpty())
    }

    @Test fun mixedDropRenameAndRemovalPreservePlaybackAndStoredReferences() = runBlocking<Unit> {
        val temp = Files.createTempDirectory("desktop-file-actions").toFile()
        val folder = File(temp, "旧フォルダ").apply { mkdir() }
        val nested = File(folder, "child").apply { mkdir() }
        val sibling = File(temp, "旧フォルダ-other").apply { mkdir() }
        val library = DesktopMidiFiles(File(System.getProperty("midi.demo.dir")))
        val bytes = library.resolve(library.demos().first()).readBytes()
        val track = File(folder, "曲.MID").apply { writeBytes(bytes) }
        val child = File(nested, "nested.midi").apply { writeBytes(bytes) }
        val neighbor = File(sibling, "other.mid").apply { writeBytes(bytes) }
        val loose = File(temp, "loose.MID").apply { writeBytes(bytes) }
        val ignored = File(temp, "text.txt").apply { writeText("text") }
        val font = File(folder, "test.dls").apply { writeBytes(testDls(percussion = false)) }
        val cache = File(temp, "cache")
        val store = DesktopStore(File(temp, "settings.properties"))
        try {
            DesktopController(store, library, BassAudio(0), cache).use { player ->
                suspend fun awaitState(predicate: (DesktopState) -> Boolean) =
                    withTimeout(30_000) { player.state.first(predicate) }
                assertNull(awaitState { it.audioReady || it.error != null }.error)
                player.setFont(font)
                assertNull(awaitState { it.soundFont != null && !it.soundFontLoading || it.error != null }.error)
                assertFalse(player.handleDroppedFiles(listOf(ignored)))
                assertTrue(player.handleDroppedFiles(listOf(loose, folder, ignored)))
                val dropped = awaitState { it.audio.playing && it.files.size == 2 || it.error != null }
                assertNull(dropped.error)
                assertEquals(loose.canonicalPath, dropped.current)
                assertFalse(loose.canonicalPath in dropped.files)
                player.importFiles(listOf(sibling)).join()
                player.createPlaylist("Saved").join()
                player.addToPlaylist(track.canonicalPath, null).join()
                player.addToPlaylist(child.canonicalPath, null).join()
                player.openFiles(listOf(track, child, neighbor)).join()
                awaitState { it.audio.playing }
                player.seek(1000).join()
                player.renameFolder(folder.path, "../escape").join()
                assertNotNull(player.state.value.error)
                assertTrue(folder.isDirectory)
                player.dismissError()
                player.renameFolder(folder.path, sibling.name).join()
                assertNotNull(player.state.value.error)
                assertTrue(track.isFile)
                player.dismissError()
                player.renameFolder(folder.path, "新フォルダ").join()
                val renamed = File(temp, "新フォルダ")
                val newTrack = File(renamed, track.name)
                val newChild = File(renamed, "child/${child.name}")
                val state = player.state.value
                assertNull(state.error)
                assertFalse(folder.exists())
                assertTrue(newTrack.isFile)
                assertEquals(newTrack.canonicalPath, state.current)
                assertTrue(state.audio.playing)
                assertTrue(state.audio.positionMs >= 1000)
                assertEquals(File(renamed, font.name).canonicalPath, store.load().soundFont)
                assertEquals(setOf(newTrack.canonicalPath, newChild.canonicalPath, neighbor.canonicalPath), state.files.toSet())
                assertEquals(listOf(newTrack.canonicalPath, newChild.canonicalPath), store.load().playlists.single().paths)
                player.next(1).join()
                assertEquals(newChild.canonicalPath, player.state.value.current)
                player.next(1).join()
                assertEquals(neighbor.canonicalPath, player.state.value.current)
                player.removeLibraryFolder(renamed.path).join()
                assertTrue(newTrack.isFile)
                assertEquals(setOf(newChild.canonicalPath, neighbor.canonicalPath), store.load().files.toSet())
                assertEquals(listOf(newTrack.canonicalPath, newChild.canonicalPath), store.load().playlists.single().paths)
            }
        } finally {
            // Remove only the individually known test fixtures, including the renamed location.
            for (root in listOf(folder, File(temp, "新フォルダ"))) {
                File(root, "child/${child.name}").delete()
                File(root, "child").delete()
                File(root, track.name).delete()
                File(root, font.name).delete()
                root.delete()
            }
            neighbor.delete(); sibling.delete(); loose.delete(); ignored.delete(); font.delete()
            cache.listFiles()?.forEach { it.delete() }; cache.delete()
            File(temp, "settings.properties").delete(); temp.delete()
        }
    }
}
