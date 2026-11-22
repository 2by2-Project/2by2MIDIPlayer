package jp.project2by2.musicplayer.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderHierarchyTest {
    @Test
    fun levelsRunFromTheRootToTheFolder() {
        assertEquals(
            listOf("/", "/storage", "/storage/emulated", "/storage/emulated/0", "/storage/emulated/0/Download"),
            absolutePathLevels("/storage/emulated/0/Download")
        )
        assertEquals(listOf("/"), absolutePathLevels("/"))
        assertEquals(listOf("0", "Download", "/"), listOf("/storage/emulated/0", "/storage/emulated/0/Download/", "/").map(::absolutePathName))
    }

    @Test
    fun parentMovesExactlyOneLevelAndStopsAtRoot() {
        assertEquals("/storage/emulated", absolutePathParent("/storage/emulated/0"))
        assertEquals("/", absolutePathParent("/storage"))
        assertNull(absolutePathParent("/"))
    }

    @Test
    fun withinPathMatchesWholeNamesOnly() {
        assertTrue(isWithinPath("/music/a", "/music"))
        assertTrue(isWithinPath("/music", "/music"))
        assertTrue(isWithinPath("/music", "/"))
        assertFalse(isWithinPath("/musics", "/music"))
    }

    @Test
    fun childFoldersCombineDiskListingWithPathsTowardsReachableFolders() {
        val volumes = listOf("/storage/emulated/0", "/storage/1234-ABCD")
        // "/" and "/storage" cannot be listed on Android; volumes still lead the way down.
        assertEquals(listOf("/storage"), explorerChildFolders("/", emptyList(), volumes).map { it.key })
        assertEquals(listOf("1234-ABCD", "emulated"), explorerChildFolders("/storage", emptyList(), volumes).map { it.name })
        assertEquals(listOf("/storage/emulated/0"), explorerChildFolders("/storage/emulated", emptyList(), volumes).map { it.key })
        // Listed folders are shown with or without music; music folders the listing missed are added.
        val listed = listOf("/storage/emulated/0/Download", "/storage/emulated/0/Music")
        val music = listOf("/storage/emulated/0/Music/Game/Album", "/storage/emulated/0/Ringtones")
        assertEquals(
            listOf("Download", "Music", "Ringtones"),
            explorerChildFolders("/storage/emulated/0", listed, volumes + music).map { it.name }
        )
        // Same names in different parents remain separate.
        assertEquals(listOf("/storage/emulated/0/Music/Game"), explorerChildFolders("/storage/emulated/0/Music", emptyList(), music).map { it.key })
        assertEquals(emptyList(), explorerChildFolders("/storage/emulated/0/Music/Game/Album", emptyList(), music))
    }

    @Test
    fun levelsWithinStartAtTheInnermostVolume() {
        val volumes = listOf("/storage/emulated/0", "/storage/1234-ABCD")
        assertEquals("/storage/emulated/0", containingRoot("/storage/emulated/0/Music", volumes))
        assertNull(containingRoot("/storage/emulated", volumes))
        assertEquals("/a/b", containingRoot("/a/b/c", listOf("/a", "/a/b")))
        assertEquals(
            listOf("/storage/emulated/0", "/storage/emulated/0/Music"),
            pathLevelsWithin("/storage/emulated/0/Music", volumes)
        )
        assertEquals(listOf("/storage/1234-ABCD"), pathLevelsWithin("/storage/1234-ABCD", volumes))
        assertEquals(listOf("/", "/storage"), pathLevelsWithin("/storage", volumes))
    }
}
