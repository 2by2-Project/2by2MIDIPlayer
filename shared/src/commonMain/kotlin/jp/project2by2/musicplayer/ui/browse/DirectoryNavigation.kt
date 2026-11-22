package jp.project2by2.musicplayer.ui.browse

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
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
 * Grid/List toggle. The header floats over the list and follows its scrolling, sliding away while
 * scrolling down and back in on any upward scroll, like an enter-always app bar. It never resizes
 * the list, so scrolling stays steady. [content] must apply the given padding to its scrollable
 * content so the first rows start below the header.
 * Navigation up is left to the app bar and system back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowseHeader(
    summary: String,
    viewMode: FolderViewMode,
    onViewModeChange: (FolderViewMode) -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    // Pixels the header is pushed up, from 0 (shown) to its height (hidden).
    var headerOffsetPx by rememberSaveable { mutableFloatStateOf(0f) }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerScrollBehavior = remember {
        object : NestedScrollConnection {
            // Driven by what the list actually scrolled, so a list that cannot scroll keeps its header
            // and the header is always fully shown again by the time the list is back at the top.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y != 0f) {
                    headerOffsetPx = (headerOffsetPx - consumed.y).coerceIn(0f, headerHeightPx.toFloat())
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // Settle a half-hidden header once a touch scroll ends.
                val height = headerHeightPx.toFloat()
                if (headerOffsetPx > 0f && headerOffsetPx < height) {
                    val target = if (headerOffsetPx < height / 2f) 0f else height
                    animate(headerOffsetPx, target) { value, _ -> headerOffsetPx = value }
                }
                return Velocity.Zero
            }
        }
    }
    // The header is measured first so the list gets its final top padding on the very first frame.
    SubcomposeLayout(Modifier.fillMaxSize().clipToBounds().nestedScroll(headerScrollBehavior)) { constraints ->
        val header = subcompose(FolderBrowseSlot.Header) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                FolderViewModeButton(viewMode, onViewModeChange)
            }
        }.map { it.measure(constraints.copy(minHeight = 0)) }
        val height = header.maxOfOrNull { it.height } ?: 0
        if (height != headerHeightPx) {
            headerHeightPx = height
            headerOffsetPx = headerOffsetPx.coerceAtMost(height.toFloat())
        }
        val topPadding = height.toDp()
        val body = subcompose(FolderBrowseSlot.Content) { content(PaddingValues(top = topPadding)) }
            .map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.forEach { it.place(0, 0) }
            header.forEach {
                it.placeWithLayer(0, 0) {
                    translationY = -headerOffsetPx
                    alpha = if (headerHeightPx == 0) 1f else 1f - headerOffsetPx / headerHeightPx
                }
            }
        }
    }
}

private enum class FolderBrowseSlot { Header, Content }

/** A single icon button that switches between Grid and List; it shows the view a tap switches to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderViewModeButton(viewMode: FolderViewMode, onViewModeChange: (FolderViewMode) -> Unit) {
    val next = if (viewMode == FolderViewMode.Grid) FolderViewMode.List else FolderViewMode.Grid
    val label = playerString(if (next == FolderViewMode.Grid) "view_switch_to_grid" else "view_switch_to_list")
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = { onViewModeChange(next) }) {
            Crossfade(targetState = next, label = "FolderViewModeIcon") { mode ->
                Icon(
                    imageVector = if (mode == FolderViewMode.Grid) Icons.Default.GridView else Icons.Default.ViewList,
                    contentDescription = label
                )
            }
        }
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
