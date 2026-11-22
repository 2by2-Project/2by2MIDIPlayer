package jp.project2by2.musicplayer.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderHierarchyTest {
    @Test
    fun intermediateFoldersAndDirectTracksKeepTheirHierarchy() {
        val folders = libraryFolderHierarchy(listOf("Music/Game/Album", "Music", "Music/Game/Album", "Download", "assets_demo", ""))
        assertEquals(listOf("Download", "Music"), childLibraryFolders(folders, null).map { it.key })
        assertEquals(listOf("Music/Game"), childLibraryFolders(folders, "Music").map { it.key })
        assertEquals(listOf("Music/Game/Album"), childLibraryFolders(folders, "Music/Game").map { it.key })
        assertEquals(emptyList(), childLibraryFolders(folders, "Music/Game/Album"))
    }

    @Test
    fun sameNamesInDifferentParentsRemainSeparate() {
        val folders = libraryFolderHierarchy(listOf("Music/Album", "Download/Album"))
        assertEquals(listOf("Music/Album"), childLibraryFolders(folders, "Music").map { it.key })
        assertEquals(listOf("Download/Album"), childLibraryFolders(folders, "Download").map { it.key })
    }

    @Test
    fun parentMovesExactlyOneLevelAndStopsAtRoot() {
        assertEquals("Music/Game", parentLibraryFolder("Music/Game/Album"))
        assertEquals("Music", parentLibraryFolder("Music/Game"))
        assertEquals(null, parentLibraryFolder("Music"))
        assertEquals(null, parentLibraryFolder("assets_demo"))
    }

    @Test
    fun secondaryVolumeIsOneTopLevelFolder() {
        val folders = libraryFolderHierarchy(listOf("Music", "/storage/1234-ABCD/Music/Game", "/storage/1234-ABCD"))
        assertEquals(listOf("/storage/1234-ABCD", "Music"), childLibraryFolders(folders, null).map { it.key })
        assertEquals("1234-ABCD", folders.first { it.key == "/storage/1234-ABCD" }.name)
        assertEquals(listOf("/storage/1234-ABCD/Music"), childLibraryFolders(folders, "/storage/1234-ABCD").map { it.key })
        assertEquals("/storage/1234-ABCD", parentLibraryFolder("/storage/1234-ABCD/Music"))
        assertEquals(null, parentLibraryFolder("/storage/1234-ABCD"))
    }
}
