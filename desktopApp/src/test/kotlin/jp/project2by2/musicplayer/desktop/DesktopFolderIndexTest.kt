package jp.project2by2.musicplayer.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DesktopFolderIndexTest {
    private lateinit var root: File
    private lateinit var home: File

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("folder-index").toFile().canonicalFile
        home = File(root, "home/user").apply { mkdirs() }
    }

    @AfterTest fun tearDown() { root.deleteRecursively() }

    private fun midi(path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("MThd") }
    private fun index(cache: File? = null) = DesktopFolderIndex(home = home, cacheFile = cache, windows = false, roots = { listOf(root) })

    @Test fun homeScanShowsOnlyFoldersLeadingToMidiAndSkipsHiddenOnes() {
        midi("home/user/Music/Game/Album/a.mid")
        midi("home/user/Music/b.MIDI")
        midi("home/user/Documents/readme.txt")
        midi("home/user/.cache/hidden.mid")
        midi("home/user/project/node_modules/pkg/c.mid")
        File(home, "Empty").mkdirs()
        val index = index().apply { scanHome() }

        assertEquals(listOf("Music"), index.children(home.path).map { it.name })
        assertEquals(listOf("Game"), index.children(File(home, "Music").path).map { it.name })
        assertEquals(listOf(File(home, "Music/b.MIDI").path), index.tracks(File(home, "Music").path))
        // Search covers the folder and everything below it.
        assertEquals(2, index.filesUnder(File(home, "Music").path).size)
        assertEquals(emptyList(), index.children(File(home, "Music/Game/Album").path))
    }

    @Test fun parentWalksUpToTheFilesystemRootAndRememberedFolderFallsBack() {
        val index = index()
        assertEquals(File(home.parentFile.path).path, index.parent(home.path))
        assertNull(index.parent("/"))
        // A remembered folder that disappeared resolves to its nearest existing ancestor, then home.
        assertEquals(home.path, index.resolve(File(home, "gone/deeper").path))
        assertEquals(home.path, index.resolve(null))
        assertEquals(DesktopFolderIndex.DRIVES, DesktopFolderIndex(home = home, windows = true).resolve(DesktopFolderIndex.DRIVES))
        // On Windows, a drive root's parent is the drive list.
        assertEquals(DesktopFolderIndex.DRIVES, DesktopFolderIndex(home = home, windows = true).parent("C:\\"))
        assertNull(DesktopFolderIndex(home = home, windows = true).parent(DesktopFolderIndex.DRIVES))
    }

    @Test fun foldersOutsideHomeAreProbedAndCached() {
        midi("home/user/Music/a.mid")
        midi("home/other/Songs/deep/b.mid")
        File(root, "home/empty/nothing").mkdirs()
        midi("srv/media/c.mid")
        val cache = File(root, "cache.properties")
        val index = index(cache).apply { scanHome() }

        index.prepare(File(root, "home").path)
        assertEquals(listOf("other", "user"), index.children(File(root, "home").path).map { it.name })
        index.prepare(root.path)
        assertEquals(listOf("home", "srv"), index.children(root.path).map { it.name })

        // The next launch shows the same folders before any scan.
        val restored = index(cache).apply { loadCache() }
        assertEquals(listOf("home", "srv"), restored.children(root.path).map { it.name })
    }

    @Test fun renamedFolderKeepsItsIndexedFiles() {
        midi("home/user/Old/a.mid")
        val index = index().apply { scanHome() }
        val old = File(home, "Old")
        val renamed = File(home, "New")
        assertTrue(old.renameTo(renamed))
        index.remap(old.path, renamed.path)
        assertEquals(listOf("New"), index.children(home.path).map { it.name })
        assertEquals(listOf(File(renamed, "a.mid").path), index.filesUnder(renamed.path))
    }
}
