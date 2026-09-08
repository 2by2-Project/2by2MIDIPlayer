@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.project2by2.musicplayer.ui.browse

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import jp.project2by2.musicplayer.*
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.*
import jp.project2by2.musicplayer.ui.player.playerString
import jp.project2by2.musicplayer.ui.player.formatPlayerDuration

@Composable
fun TrackList(
    items: List<LibraryTrack>,
    listContext: MidiListContext,
    isLoading: Boolean = false,
    isEditMode: Boolean = false,
    onMoveItem: (Long, Int) -> Unit = { _, _ -> },
    onRemoveItem: (Long) -> Unit = {},
    selectedUri: String?,
    availability: Map<String, MidiFileAvailability> = emptyMap(),
    animationToken: Long = 0L,
    onItemClick: (LibraryTrack) -> Unit,
    onAddToPlaylist: (LibraryTrack) -> Unit,
    onQueueNext: (LibraryTrack) -> Unit,
    onMissingItemDetected: (LibraryTrack) -> Unit = {},
    actions: @Composable (LibraryTrack, () -> Unit) -> Unit = { _, close -> TextButton(onClick = close) { Text("Close") } }
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val defaultRowHeightPx = with(density) { 72.dp.toPx() }
    var measuredRowHeightPx by remember { mutableFloatStateOf(defaultRowHeightPx) }
    var activeDragItemId by remember { mutableStateOf<Long?>(null) }
    var activeDragOffsetPx by remember { mutableFloatStateOf(0f) }
    val removingItemIds = remember { mutableStateListOf<Long>() }

    if (items.isEmpty()) {
        if (isLoading) {
            LoadingMidiState()
        } else if (listContext == MidiListContext.Search) {
            EmptyMidiState(
                icon = Icons.Default.Search,
                message = playerString("info_no_matching_files")
            )
        } else {
            EmptyMidiState(
                icon = Icons.Default.Folder,
                message = playerString("info_no_mid_files_found")
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 72.dp)
    ) {
        lazyItemsIndexed(
            items = items,
            key = { index: Int, item: LibraryTrack -> item.playlistItemId ?: "${item.uri}#$index" }
        ) { index: Int, item: LibraryTrack ->
            val itemAvailability = availability[item.uri.toString()] ?: MidiFileAvailability.Unknown
            LaunchedEffect(item.uri, itemAvailability, listContext) {
                if (itemAvailability == MidiFileAvailability.Missing && listContext != MidiListContext.Playlist) {
                    onMissingItemDetected(item)
                }
            }
            val itemId = item.playlistItemId
            val activeId = activeDragItemId
            val activeIndex = if (activeId != null) {
                items.indexOfFirst { it.playlistItemId == activeId }
            } else {
                -1
            }
            val rowHeight = measuredRowHeightPx.coerceAtLeast(1f)
            val projectedShift = if (activeIndex >= 0) {
                (activeDragOffsetPx / rowHeight).roundToInt()
            } else {
                0
            }
            val projectedTargetIndex = if (activeIndex >= 0) {
                (activeIndex + projectedShift).coerceIn(0, items.lastIndex)
            } else {
                -1
            }
            val shiftTarget = when {
                activeIndex < 0 || index == activeIndex -> 0f
                activeIndex < projectedTargetIndex && index in (activeIndex + 1)..projectedTargetIndex -> -rowHeight
                activeIndex > projectedTargetIndex && index in projectedTargetIndex until activeIndex -> rowHeight
                else -> 0f
            }
            val animatedShift by animateFloatAsState(
                targetValue = shiftTarget,
                animationSpec = if (activeIndex >= 0) tween(durationMillis = 120) else snap(),
                label = "playlist_neighbor_shift"
            )

            StaggeredFadeInItem(
                itemKey = item.playlistItemId ?: item.uri.toString(),
                index = index,
                animationToken = animationToken,
                enabled = !isEditMode && listContext != MidiListContext.Playlist
            ) {
                MidiFileRow(
                    item = item,
                    availability = itemAvailability,
                    rowModifier = Modifier.graphicsLayer { translationY = animatedShift },
                    isSelected = item.uri == selectedUri,
                    showReorderHandle = isEditMode,
                    isRemoving = itemId != null && removingItemIds.contains(itemId),
                    onDragStart = {
                        if (!isEditMode || itemId == null) return@MidiFileRow
                        if (removingItemIds.contains(itemId)) return@MidiFileRow
                        activeDragItemId = itemId
                        activeDragOffsetPx = 0f
                    },
                    onDragDelta = { dy ->
                        if (!isEditMode || activeDragItemId != itemId) return@MidiFileRow
                        activeDragOffsetPx += dy
                    },
                    onDragEnd = {
                        if (!isEditMode || itemId == null) return@MidiFileRow
                        val finalOffsetPx = activeDragOffsetPx
                        val fromIndex = items.indexOfFirst { it.playlistItemId == itemId }
                        if (fromIndex >= 0) {
                            val toIndex = (fromIndex + (finalOffsetPx / rowHeight).roundToInt())
                                .coerceIn(0, items.lastIndex)
                            val delta = toIndex - fromIndex
                            if (delta != 0) {
                                activeDragOffsetPx = 0f
                                activeDragItemId = null
                                onMoveItem(itemId, delta)
                            } else {
                                activeDragOffsetPx = 0f
                                activeDragItemId = null
                            }
                        } else {
                            activeDragOffsetPx = 0f
                            activeDragItemId = null
                        }
                    },
                    onDeleteClick = {
                        if (!isEditMode || itemId == null) return@MidiFileRow
                        if (removingItemIds.contains(itemId)) return@MidiFileRow
                        removingItemIds.add(itemId)
                        if (activeDragItemId == itemId) {
                            activeDragItemId = null
                            activeDragOffsetPx = 0f
                        }
                        scope.launch {
                            delay(220)
                            onRemoveItem(itemId)
                            removingItemIds.remove(itemId)
                        }
                    },
                    onRowHeightMeasured = { h ->
                        if (h > 1f) measuredRowHeightPx = h
                    },
                    onClick = { onItemClick(item) },
                    onAddToPlaylist = { onAddToPlaylist(item) },
                    onQueueNext = { onQueueNext(item) },
                    actions = { close -> actions(item, close) }
                )
            }
        }
    }

}

@Composable
private fun MidiFileRow(
    item: LibraryTrack,
    availability: MidiFileAvailability = MidiFileAvailability.Unknown,
    rowModifier: Modifier = Modifier,
    isSelected: Boolean,
    showReorderHandle: Boolean = false,
    isRemoving: Boolean = false,
    onDragStart: () -> Unit = {},
    onDragDelta: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onRowHeightMeasured: (Float) -> Unit = {},
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onQueueNext: () -> Unit,
    actions: @Composable (() -> Unit) -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    var dragVisualOffsetPx by remember(item.playlistItemId, showReorderHandle) { mutableFloatStateOf(0f) }
    var isDragging by remember(item.playlistItemId, showReorderHandle) { mutableStateOf(false) }
    var rowWidthPx by remember(item.playlistItemId, showReorderHandle) { mutableFloatStateOf(0f) }
    val animatedScale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        animationSpec = spring(stiffness = 560f, dampingRatio = 0.9f),
        label = "playlist_drag_scale"
    )
    val dismissProgress by animateFloatAsState(
        targetValue = if (isRemoving) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "playlist_remove_progress"
    )
    val editLeadingWidth by animateDpAsState(
        targetValue = if (showReorderHandle) 44.dp else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "playlist_edit_leading_width"
    )
    val deleteButtonAlpha by animateFloatAsState(
        targetValue = if (showReorderHandle) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "playlist_delete_fade"
    )
    val background = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(0.25f)
    } else {
        Color.Transparent
    }
    val contentColor = if (isSelected) {
        // The selection tint is translucent over the surface, not an opaque primaryContainer.
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onBackground
    }
    val titleAlpha = if (availability == MidiFileAvailability.Missing) 0.45f else 1f
    val secondaryText = if (availability == MidiFileAvailability.Missing) {
        playerString("summary_midi_file_missing")
    } else {
        item.displaySecondaryText()
    }
    val secondaryColor = if (availability == MidiFileAvailability.Missing) {
        MaterialTheme.colorScheme.error
    } else {
        contentColor
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (dismissProgress > 0f) {
                    Color(0xFFC62828).copy(alpha = dismissProgress)
                } else {
                    Color.Transparent
                }
            )
            .onSizeChanged {
                rowWidthPx = it.width.toFloat()
                onRowHeightMeasured(it.height.toFloat())
            }
    ) {
        Row(
            modifier = rowModifier
                .fillMaxWidth()
                .zIndex(if (isDragging) 2f else 0f)
                .graphicsLayer {
                    translationX = -rowWidthPx * dismissProgress
                    translationY = dragVisualOffsetPx
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
                .shadow(
                    elevation = if (isDragging) 8.dp else 0.dp,
                    shape = RoundedCornerShape(8.dp),
                    clip = false
                )
                .combinedClickable(
                    onClick = { if (!isRemoving) onClick() },
                    onLongClick = {
                        if (!showReorderHandle && !isRemoving) {
                            showActions = true
                        }
                    }
                )
                .then(
                    if (showReorderHandle && !isRemoving) {
                        Modifier.pointerInput(item.playlistItemId ?: item.uri) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    isDragging = true
                                    dragVisualOffsetPx = 0f
                                    onDragStart()
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val dy = dragAmount.y
                                    dragVisualOffsetPx += dy
                                    onDragDelta(dy)
                                },
                                onDragEnd = {
                                    dragVisualOffsetPx = 0f
                                    isDragging = false
                                    onDragEnd()
                                },
                                onDragCancel = {
                                    dragVisualOffsetPx = 0f
                                    isDragging = false
                                    onDragEnd()
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 0.dp, vertical = 0.dp)
                .background(background, RoundedCornerShape(4.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(editLeadingWidth)
                    .padding(start = if (showReorderHandle) 6.dp else 0.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showReorderHandle || deleteButtonAlpha > 0f) {
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier
                            .size(32.dp)
                            .graphicsLayer { alpha = deleteButtonAlpha }
                    ) {
                        Icon(
                            imageVector = Icons.Default.RemoveCircle,
                            contentDescription = null,
                            tint = Color(0xFFC62828)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(16.dp)
            ) {
                Text(
                    text = item.displayTitle(),
                    maxLines = 1,
                    color = contentColor,
                    modifier = Modifier
                        .alpha(titleAlpha)
                        .clipToBounds()
                        .basicMarquee(Int.MAX_VALUE)
                )
                if (!secondaryText.isNullOrBlank()) {
                    Text(
                        text = secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(
                            if (availability == MidiFileAvailability.Missing) 1f else if (isSelected) 0.7f else 0.5f
                        ),
                        maxLines = 1,
                        color = secondaryColor
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.loopPointMs != null) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSelected) 0.7f else 0.5f)
                    )
                }
                Text(
                    text = if (item.durationMs <= 0L) playerString("duration_placeholder") else formatPlayerDuration(item.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                    color = contentColor
                )
            }
        }
    }

    if (showActions && !showReorderHandle) actions { showActions = false }
}
@Composable
private fun LoadingMidiState() {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val upwardOffset = if (imeBottom > 0) (-96).dp else 0.dp

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.offset(y = upwardOffset)
        )
    }
}

@Composable
private fun EmptyMidiState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val upwardOffset = if (imeBottom > 0) (-96).dp else 0.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .offset(y = upwardOffset),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
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
