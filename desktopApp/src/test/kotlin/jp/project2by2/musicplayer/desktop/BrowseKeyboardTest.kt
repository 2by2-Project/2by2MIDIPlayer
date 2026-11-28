package jp.project2by2.musicplayer.desktop

import jp.project2by2.musicplayer.ui.browse.DEMO_ENTRY_KEY
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseKeyboardTest {
    private val entries = browseEntryKeys(listOf("f0", "f1", "f2", "f3", "f4"), demo = true, tracks = listOf("t0", "t1"))

    @Test fun entriesAreFoldersThenDemoThenTracks() {
        assertEquals(listOf("f0", "f1", DEMO_ENTRY_KEY, "t0"), browseEntryKeys(listOf("f0", "f1"), true, listOf("t0")))
        assertEquals(listOf("t0"), browseEntryKeys(emptyList(), false, listOf("t0")))
    }

    @Test fun listMovesOneEntryAtATimeWithoutWrapping() {
        assertEquals("f0", moveBrowseHighlight(entries, 5, false, null, down = true))
        assertEquals("t1", moveBrowseHighlight(entries, 5, false, null, down = false))
        assertEquals("f1", moveBrowseHighlight(entries, 5, false, "f0", down = true))
        assertEquals("f0", moveBrowseHighlight(entries, 5, false, "f0", down = false))
        assertEquals("t1", moveBrowseHighlight(entries, 5, false, "t1", down = true))
        assertEquals(DEMO_ENTRY_KEY, moveBrowseHighlight(entries, 5, false, "t0", down = false))
        assertEquals(null, moveBrowseHighlight(emptyList(), 0, false, null, down = true))
    }

    @Test fun gridMovesByRowsAndContinuesBelowTheFolders() {
        // Rows: (f0 f1) (f2 f3) (f4)
        assertEquals("f2", moveBrowseHighlight(entries, 5, true, "f0", down = true))
        assertEquals("f3", moveBrowseHighlight(entries, 5, true, "f1", down = true))
        assertEquals("f4", moveBrowseHighlight(entries, 5, true, "f3", down = true))
        assertEquals("f1", moveBrowseHighlight(entries, 5, true, "f3", down = false))
        assertEquals("f0", moveBrowseHighlight(entries, 5, true, "f0", down = false))
        assertEquals(DEMO_ENTRY_KEY, moveBrowseHighlight(entries, 5, true, "f4", down = true))
        assertEquals("f4", moveBrowseHighlight(entries, 5, true, DEMO_ENTRY_KEY, down = false))
    }

    @Test fun listIndexFollowsTheRowsThatAreShown() {
        assertEquals(1, browseListIndex(entries, 5, false, "f1"))
        assertEquals(6, browseListIndex(entries, 5, false, "t0"))
        assertEquals(1, browseListIndex(entries, 5, true, "f3"))
        assertEquals(3, browseListIndex(entries, 5, true, DEMO_ENTRY_KEY))
        assertEquals(4, browseListIndex(entries, 5, true, "t0"))
        assertEquals(-1, browseListIndex(entries, 5, true, "missing"))
    }
}
