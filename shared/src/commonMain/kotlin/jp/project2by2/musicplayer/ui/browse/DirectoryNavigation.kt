package jp.project2by2.musicplayer.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.ui.player.playerString

@Composable
fun DirectoryPathHeader(path: String, viewMode: FolderViewMode, onViewModeChange: (FolderViewMode) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(path, modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), maxLines = 1,
            style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onViewModeChange(if (viewMode == FolderViewMode.Grid) FolderViewMode.List else FolderViewMode.Grid) }) {
            Icon(if (viewMode == FolderViewMode.Grid) Icons.Default.ViewList else Icons.Default.GridView,
                playerString(if (viewMode == FolderViewMode.Grid) "view_list" else "view_grid"))
        }
    }
    HorizontalDivider()
}

fun LazyListScope.directoryEntries(
    folders: List<LibraryFolder>,
    viewMode: FolderViewMode,
    onParent: (() -> Unit)?,
    onFolder: (LibraryFolder) -> Unit,
    demo: (() -> Unit)? = null,
    cover: @Composable (LibraryFolder) -> ImageBitmap? = { null }
) {
    if (onParent != null) item(key = "directory:parent") {
        ListItem(headlineContent = { Text("..") }, leadingContent = { Icon(Icons.Default.ArrowUpward, null) },
            modifier = Modifier.clickable(onClick = onParent))
    }
    if (viewMode == FolderViewMode.Grid) {
        folders.chunked(2).forEach { row ->
            item(key = "directory:${row.first().key}") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { folder ->
                        Box(Modifier.weight(1f)) { FolderCard(folder, cover = cover, onClick = { onFolder(folder) }, onActions = null) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        folders.forEach { folder ->
            item(key = "directory:${folder.key}") { FolderListRow(folder, cover = cover, onClick = { onFolder(folder) }, onActions = null) }
        }
    }
    if (demo != null) item(key = "directory:demo") { DemoMusicButton(demo) }
}
