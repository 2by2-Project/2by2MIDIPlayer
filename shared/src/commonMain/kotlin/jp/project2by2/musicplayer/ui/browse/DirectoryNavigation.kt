package jp.project2by2.musicplayer.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.ui.player.playerString

/** "3 folders · 12 songs"; zero counts are left out. */
@Composable
fun folderBrowseSummary(folderCount: Int, trackCount: Int): String = listOfNotNull(
    countLabel("browse_summary_folders", folderCount),
    countLabel("browse_summary_tracks", trackCount)
).joinToString(" · ")

@Composable
private fun countLabel(key: String, count: Int): String? = when (count) {
    0 -> null
    1 -> playerString("${key}_one")
    else -> playerString(key).replace("{n}", count.toString())
}

/**
 * Folder browsing chrome shared by the top level and every directory: a count summary and the
 * Grid/List switch, hidden while scrolling down. Navigation up is left to the app bar and system back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowseHeader(
    summary: String,
    viewMode: FolderViewMode,
    onViewModeChange: (FolderViewMode) -> Unit,
    content: @Composable () -> Unit
) {
    var headerVisible by rememberSaveable { mutableStateOf(true) }
    val headerScrollBehavior = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                when {
                    available.y > 1f -> headerVisible = true
                    available.y < -1f -> headerVisible = false
                }
                return Offset.Zero
            }
        }
    }
    Column(Modifier.fillMaxSize().nestedScroll(headerScrollBehavior)) {
        AnimatedVisibility(
            visible = headerVisible,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = viewMode == FolderViewMode.Grid,
                        onClick = { onViewModeChange(FolderViewMode.Grid) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {},
                        label = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(imageVector = Icons.Default.GridView, contentDescription = playerString("view_grid"))
                            }
                        }
                    )
                    SegmentedButton(
                        selected = viewMode == FolderViewMode.List,
                        onClick = { onViewModeChange(FolderViewMode.List) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {},
                        label = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(imageVector = Icons.Default.ViewList, contentDescription = playerString("view_list"))
                            }
                        }
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** Child folders listed ahead of a directory's tracks, in the same Grid/List style as the top level. */
fun LazyListScope.directoryEntries(
    folders: List<LibraryFolder>,
    viewMode: FolderViewMode,
    onFolder: (LibraryFolder) -> Unit,
    demo: (() -> Unit)? = null,
    cover: @Composable (LibraryFolder) -> ImageBitmap? = { null },
    onFolderActions: ((LibraryFolder) -> Unit)? = null
) {
    if (viewMode == FolderViewMode.Grid) {
        folders.chunked(2).forEach { row ->
            item(key = "directory:${row.first().key}") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { folder ->
                        Box(Modifier.weight(1f)) {
                            FolderCard(folder, cover = cover, onClick = { onFolder(folder) },
                                onActions = onFolderActions?.let { { it(folder) } })
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        folders.forEach { folder ->
            item(key = "directory:${folder.key}") {
                FolderListRow(folder, cover = cover, onClick = { onFolder(folder) },
                    onActions = onFolderActions?.let { { it(folder) } })
            }
        }
    }
    if (demo != null) item(key = "directory:demo") { DemoMusicButton(demo) }
}
