package jp.project2by2.musicplayer.ui.browse

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import jp.project2by2.musicplayer.ui.player.playerString

/**
 * One level of the address bar; [key] identifies the folder (or virtual location) it opens. A segment
 * with [choices] opens a menu of them instead, such as the storage volumes at a volume's root.
 */
data class AddressSegment(val key: String, val label: String, val choices: List<AddressSegment> = emptyList())

/**
 * Browse's address bar on every platform: [folders] from the root to the open folder, followed by the
 * sample music when it is open. Hosts supply the folder levels and open a tapped folder.
 */
@Composable
fun LibraryAddressBar(
    folders: List<AddressSegment>,
    demoOpen: Boolean,
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Back gesture progress on phones (0..1): the open level fades and the bar scrolls back to its parent.
     * Read only while drawing and scrolling, so following the finger never recomposes the screen.
     */
    backProgress: () -> Float = { 0f },
) {
    val demo = playerString("folder_demo_name")
    val segments = folders + listOfNotNull(if (demoOpen) AddressSegment(DEMO_ADDRESS, demo) else null)
    AddressBar(segments, modifier = modifier, backProgress = backProgress, onSegmentClick = { segment ->
        if (segment.key != DEMO_ADDRESS) onOpenFolder(segment.key)
    })
}

private const val DEMO_ADDRESS = "address:demo"

/**
 * Explorer-style location bar below the app bar: one pill per level, separated by chevrons. The
 * current level (last) uses the accent color; tapping an ancestor opens it, so no back button is needed.
 */
@Composable
fun AddressBar(
    segments: List<AddressSegment>,
    onSegmentClick: (AddressSegment) -> Unit,
    modifier: Modifier = Modifier,
    backProgress: () -> Float = { 0f },
) {
    val listState = rememberLazyListState()
    // Scroll applied for the back preview, so it can follow the gesture both ways.
    var backScroll by remember { mutableFloatStateOf(0f) }
    val lastIndex by rememberUpdatedState(segments.lastIndex)
    LaunchedEffect(listState) {
        snapshotFlow { backProgress().coerceIn(0f, 1f) }.collect { progress ->
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
            val wanted = if (last != null) progress * last.size else 0f
            if (wanted != backScroll) backScroll -= listState.scrollBy(-(wanted - backScroll))
        }
    }
    // Deep paths overflow the width; keep the current level in view, also while the pane is resized.
    LaunchedEffect(segments) {
        backScroll = 0f
        if (segments.isEmpty()) return@LaunchedEffect
        listState.animateScrollToItem(segments.lastIndex)
        snapshotFlow { listState.layoutInfo.viewportSize.width }.collect { listState.scrollToItem(segments.lastIndex) }
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(segments, key = { _, segment -> segment.key }) { index, segment ->
            val leaving = index == segments.lastIndex && index > 0
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = if (leaving) Modifier.graphicsLayer { alpha = 1f - backProgress().coerceIn(0f, 1f) } else Modifier) {
                if (index > 0) Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.padding(horizontal = 2.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                if (segment.choices.isEmpty()) {
                    AddressPill(segment.label, current = index == segments.lastIndex) { onSegmentClick(segment) }
                } else {
                    AddressChoicePill(segment, current = index == segments.lastIndex, onChoiceClick = onSegmentClick)
                }
            }
        }
    }
}

/** A pill with a menu of [AddressSegment.choices] that grows out to the right, below the pill. */
@Composable
private fun AddressChoicePill(segment: AddressSegment, current: Boolean, onChoiceClick: (AddressSegment) -> Unit) {
    val menu = remember { MutableTransitionState(false) }
    var pillHeight by remember { mutableIntStateOf(0) }
    val arrowRotation by animateFloatAsState(if (menu.targetState) 180f else 0f, tween(MENU_ANIMATION_MS))
    Box(Modifier.onSizeChanged { pillHeight = it.height }) {
        AddressPill(segment.label, current, trailing = {
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp).rotate(arrowRotation))
        }) { menu.targetState = !menu.targetState }
        // Kept while the closing animation runs.
        if (menu.currentState || menu.targetState) Popup(
            offset = IntOffset(0, pillHeight),
            onDismissRequest = { menu.targetState = false },
            properties = PopupProperties(focusable = true),
        ) {
            AnimatedVisibility(
                visibleState = menu,
                enter = expandHorizontally(tween(MENU_ANIMATION_MS), expandFrom = Alignment.Start) + fadeIn(tween(MENU_ANIMATION_MS)),
                exit = shrinkHorizontally(tween(MENU_ANIMATION_MS), shrinkTowards = Alignment.Start) + fadeOut(tween(MENU_ANIMATION_MS)),
            ) {
                Surface(
                    modifier = Modifier.padding(top = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp)) {
                        segment.choices.forEach { choice ->
                            val selected = choice.key == segment.key
                            DropdownMenuItem(
                                text = { Text(choice.label, maxLines = 1) },
                                onClick = {
                                    menu.targetState = false
                                    if (!selected) onChoiceClick(choice)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Check, contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val MENU_ANIMATION_MS = 200

@Composable
private fun AddressPill(label: String, current: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val colors = if (current) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onClick,
        // Slimmer than a regular button; widthIn replaces the 58dp default minimum so "/" stays compact.
        modifier = Modifier.height(32.dp).widthIn(min = 40.dp),
        colors = colors,
        contentPadding = PaddingValues(start = 14.dp, end = if (trailing != null) 8.dp else 14.dp),
    ) {
        // No weight: the bar scrolls, so its width is unbounded and a weighted label would shrink to nothing.
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        trailing?.invoke()
    }
}
