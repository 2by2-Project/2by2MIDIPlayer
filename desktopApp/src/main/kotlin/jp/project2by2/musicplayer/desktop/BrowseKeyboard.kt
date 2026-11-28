package jp.project2by2.musicplayer.desktop

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import jp.project2by2.musicplayer.ui.browse.DEMO_ENTRY_KEY

/**
 * Keyboard selection in the library list. Entries are identified by key: a folder's path,
 * [DEMO_ENTRY_KEY] for the sample-music button, or a track's path, in the order the list shows them
 * (folders, sample button, tracks).
 */
internal fun browseEntryKeys(folders: List<String>, demo: Boolean, tracks: List<String>): List<String> =
    folders + listOfNotNull(DEMO_ENTRY_KEY.takeIf { demo }) + tracks

/**
 * The entry one step [down] (or up) from [current]; no selection starts at the first (last) entry.
 * Does not wrap. A grid of folder cards has two columns, so moving between folders steps a row.
 */
internal fun moveBrowseHighlight(entries: List<String>, folderCount: Int, grid: Boolean, current: String?, down: Boolean): String? {
    if (entries.isEmpty()) return null
    val index = entries.indexOf(current)
    if (index < 0) return if (down) entries.first() else entries.last()
    val next = if (grid && index < folderCount) {
        when {
            !down -> if (index >= 2) index - 2 else index
            index + 2 < folderCount -> index + 2
            // The last row (or a row above a lone card in the last row) continues below the folders.
            index / 2 == (folderCount - 1) / 2 -> folderCount
            else -> folderCount - 1
        }
    } else index + if (down) 1 else -1
    return entries[next.coerceIn(0, entries.lastIndex)]
}

/** Position in the lazy list of the item that shows [key]; folder cards share a row two at a time in the grid. */
internal fun browseListIndex(entries: List<String>, folderCount: Int, grid: Boolean, key: String): Int {
    val index = entries.indexOf(key)
    if (index < 0) return -1
    if (!grid) return index
    val folderItems = (folderCount + 1) / 2
    return if (index < folderCount) index / 2 else folderItems + (index - folderCount)
}

/** Scrolls just far enough for item [index] to be fully shown below the floating header and above the bottom bar. */
internal suspend fun LazyListState.ensureVisible(index: Int) {
    if (index < 0) return
    val info = layoutInfo
    val top = info.viewportStartOffset + info.beforeContentPadding
    val bottom = info.viewportEndOffset - info.afterContentPadding
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    when {
        item == null && index < firstVisibleItemIndex -> scrollToItem(index)
        item == null -> {
            // Below the view: bring it in at the bottom edge, sized like the rows now shown.
            val size = info.visibleItemsInfo.lastOrNull()?.size ?: 0
            scrollToItem(index, -(bottom - top - size).coerceAtLeast(0))
        }
        item.offset < top -> scrollBy((item.offset - top).toFloat())
        item.offset + item.size > bottom -> scrollBy((item.offset + item.size - bottom).toFloat())
    }
}
