@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.project2by2.musicplayer.ui.browse

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.*
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.*
import jp.project2by2.musicplayer.ui.player.playerString

@Composable
fun BrowseScreen(
    items: List<LibraryFolder>,
    cover: @Composable (LibraryFolder) -> ImageBitmap? = { null },
    gridState: LazyGridState = rememberLazyGridState(),
    listState: LazyListState = rememberLazyListState(),
    animationToken: Long = 0L,
    onFolderClick: (LibraryFolder) -> Unit,
    onDemoMusicClick: (() -> Unit)?,
    viewMode: FolderViewMode,
    onViewModeChange: (FolderViewMode) -> Unit,
    onFolderActions: ((LibraryFolder) -> Unit)? = null
) {
    FolderBrowseHeader(
        summary = folderBrowseSummary(items.size, 0),
        viewMode = viewMode,
        onViewModeChange = onViewModeChange
    ) { headerPadding ->
        if (viewMode == FolderViewMode.Grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                    top = 16.dp + headerPadding.calculateTopPadding()
                )
            ) {
                itemsIndexed(items, key = { _: Int, item: LibraryFolder -> item.key }) { index: Int, folder: LibraryFolder ->
                    StaggeredFadeInItem(
                        itemKey = folder.key,
                        index = index,
                        animationToken = animationToken
                    ) {
                        FolderCard(
                            folder = folder,
                            cover = cover,
                            onClick = { onFolderClick(folder) },
                            onActions = onFolderActions?.let { { it(folder) } }
                        )
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (onDemoMusicClick != null) DemoMusicButton(
                        onClick = onDemoMusicClick,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        } else {
            FolderList(
                items = items,
                cover = cover,
                listState = listState,
                animationToken = animationToken,
                onFolderClick = onFolderClick,
                onDemoMusicClick = onDemoMusicClick,
                onFolderActions = onFolderActions,
                contentPadding = headerPadding
            )
        }
    }
}

@Composable
private fun FolderList(
    items: List<LibraryFolder>,
    cover: @Composable (LibraryFolder) -> ImageBitmap? = { null },
    listState: LazyListState = rememberLazyListState(),
    animationToken: Long = 0L,
    onFolderClick: (LibraryFolder) -> Unit,
    onDemoMusicClick: (() -> Unit)?,
    onFolderActions: ((LibraryFolder) -> Unit)?,
    contentPadding: PaddingValues = PaddingValues()
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = 72.dp)
    ) {
        lazyItemsIndexed(items, key = { _: Int, item: LibraryFolder -> item.key }) { index: Int, folder: LibraryFolder ->
            StaggeredFadeInItem(
                itemKey = folder.key,
                index = index,
                animationToken = animationToken
            ) {
                FolderListRow(
                    folder = folder,
                    cover = cover,
                    onClick = { onFolderClick(folder) },
                    onActions = onFolderActions?.let { { it(folder) } }
                )
            }
        }
        item {
            if (onDemoMusicClick != null) DemoMusicButton(
                onClick = onDemoMusicClick,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

/** Strength of the pale highlight that marks the row selected with the keyboard. */
internal const val KeyboardHighlightAlpha = 0.10f

/** Paints the pale keyboard-selection highlight behind a list entry while [active]. */
@Composable
internal fun Modifier.keyboardHighlight(active: Boolean, shape: Shape = RoundedCornerShape(4.dp)): Modifier =
    if (active) background(MaterialTheme.colorScheme.onSurface.copy(alpha = KeyboardHighlightAlpha), shape) else this

@Composable
internal fun FolderListRow(
    folder: LibraryFolder,
    cover: @Composable (LibraryFolder) -> ImageBitmap? = { null },
    onClick: () -> Unit,
    onActions: (() -> Unit)?,
    highlighted: Boolean = false
) {
    val coverBitmap = cover(folder)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .keyboardHighlight(highlighted)
            .desktopContextClick(enabled = onActions != null) { onActions?.invoke() }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (coverBitmap != null) {
            Image(
                bitmap = coverBitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clipToBounds(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = folder.name,
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .basicMarquee(Int.MAX_VALUE),
            maxLines = 1
        )
    }
}

@Composable
internal fun FolderCard(
    folder: LibraryFolder,
    cover: @Composable (LibraryFolder) -> ImageBitmap? = { null },
    onClick: () -> Unit,
    onActions: (() -> Unit)?,
    highlighted: Boolean = false
) {
    val coverBitmap = cover(folder)
    Card(
        onClick = onClick,
        colors = if (highlighted) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = KeyboardHighlightAlpha)
                .compositeOver(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth()
            .desktopContextClick(enabled = onActions != null) { onActions?.invoke() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = folder.name,
                modifier = Modifier.padding(12.dp)
                    .clipToBounds()
                    .basicMarquee(Int.MAX_VALUE),
                maxLines = 1
            )
        }
    }
}

@Composable
fun DemoMusicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDividers: Boolean = true,
    highlighted: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDividers) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        TextButton(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .keyboardHighlight(highlighted, RoundedCornerShape(50)),
            onClick = onClick
        ) {
            Icon(
                imageVector = Icons.Filled.Audiotrack,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(playerString("button_sample_demo_music"))
        }
    }
}

@Composable
private fun StaggeredFadeInItem(
    itemKey: Any,
    index: Int,
    animationToken: Long,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    content()
}
