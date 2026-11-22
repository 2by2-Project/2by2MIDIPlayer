package jp.project2by2.musicplayer.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset

/** Narrowest pane either side of the split may be resized to. */
private val MinPaneWidth = 300.dp

/** Touch target of the divider handle; 48dp wide so it can be grabbed with a finger. */
private val DragHandleTargetWidth = 48.dp
private val DragHandleTargetHeight = 96.dp

/**
 * Whether the window has room for library and player side by side: desktop-sized windows, plus
 * Material medium/expanded windows such as unfolded foldables and tablets. Phones in landscape have
 * a compact height and keep single-screen navigation.
 */
fun isWidePlayerLayout(width: Dp, height: Dp): Boolean =
    width >= 1000.dp || (width >= 600.dp && height >= 480.dp)

/**
 * Only placement changes; hosts supply the same screen Composables at both sizes.
 *
 * [verticalFoldInWindow] is the window-space bounds (px) of a vertical hinge the panes should not
 * straddle, e.g. a foldable in book posture. While it lies inside the layout, the split follows the
 * hinge instead of the draggable divider.
 */
@Composable
fun ResponsivePlayerLayout(
    showSettings: Boolean,
    showPlayer: Boolean,
    onBack: (wide: Boolean) -> Unit,
    library: @Composable (wide: Boolean) -> Unit,
    settings: @Composable () -> Unit,
    player: @Composable (wide: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dividerModifier: Modifier = Modifier,
    verticalFoldInWindow: Rect? = null
) {
    var splitFraction by rememberSaveable { mutableStateOf<Float?>(null) }
    var windowOffsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { windowOffsetX = it.positionInWindow().x }) {
        val availableWidth = maxWidth
        val maxLibraryWidth = (availableWidth - MinPaneWidth).coerceAtLeast(MinPaneWidth)
        // Hinge edges relative to this layout, kept only when both sides can hold a pane.
        val fold = verticalFoldInWindow?.let { bounds ->
            with(density) { (bounds.left - windowOffsetX).toDp() to (bounds.right - windowOffsetX).toDp() }
        }?.takeIf { (start, end) -> start >= MinPaneWidth && availableWidth - end >= MinPaneWidth }
        val wide = fold != null || isWidePlayerLayout(maxWidth, maxHeight)
        val libraryWidth = fold?.first ?: (splitFraction?.let { availableWidth * it }
            // Default never gives the player less room than the library (e.g. 701dp foldable).
            ?: (maxWidth * 0.4f).coerceIn(360.dp, 480.dp).coerceAtMost((maxWidth / 2).value.toInt().dp))
            .coerceIn(MinPaneWidth, maxLibraryWidth)
        val playerStart = fold?.second ?: libraryWidth
        Box(Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onBack(wide); true } else false
        }) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(libraryWidth).fillMaxHeight()) { library(true) }
                    if (playerStart > libraryWidth) Spacer(Modifier.width(playerStart - libraryWidth))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (showSettings) settings() else player(true)
                    }
                }
                val dividerColor = MaterialTheme.colorScheme.outlineVariant
                if (fold != null) {
                    // The hinge is the divider; a zero-width fold still gets the hairline.
                    if (playerStart == libraryWidth) Box(Modifier
                        .offset { IntOffset(libraryWidth.roundToPx(), 0) }
                        .width(with(density) { 1.toDp() }).fillMaxHeight()
                        .drawBehind { drawRect(dividerColor) })
                } else {
                    val dragState = rememberDraggableState { delta ->
                        val currentWidth = splitFraction?.let { availableWidth * it } ?: libraryWidth
                        val nextWidth = (currentWidth.coerceIn(MinPaneWidth, maxLibraryWidth) + with(density) { delta.toDp() })
                            .coerceIn(MinPaneWidth, maxLibraryWidth)
                        splitFraction = nextWidth / availableWidth
                    }
                    val handleInteraction = remember { MutableInteractionSource() }
                    val handleActive = handleInteraction.collectIsDraggedAsState().value ||
                        handleInteraction.collectIsPressedAsState().value
                    // Overlay the panes: no visible gutter, with four physical pixels on each side.
                    Box(dividerModifier
                        .offset { IntOffset(with(density) { libraryWidth.roundToPx() } - 4, 0) }
                        .width(with(density) { 8.toDp() }).fillMaxHeight()
                        .horizontalResizePointer()
                        .drawBehind { drawRect(dividerColor, Offset(4f, 0f), Size(1f, size.height)) }
                        .draggable(dragState, Orientation.Horizontal))
                    // Material pane drag handle: a finger-sized target centred on the divider.
                    val handleColor = if (handleActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                    Box(Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset((libraryWidth - DragHandleTargetWidth / 2).roundToPx(), 0) }
                        .size(DragHandleTargetWidth, DragHandleTargetHeight)
                        .horizontalResizePointer()
                        .draggable(dragState, Orientation.Horizontal, interactionSource = handleInteraction)
                        .drawBehind {
                            val handleWidth = (if (handleActive) 12.dp else 4.dp).toPx()
                            val handleHeight = 48.dp.toPx()
                            drawRoundRect(
                                handleColor,
                                topLeft = Offset((size.width - handleWidth) / 2, (size.height - handleHeight) / 2),
                                size = Size(handleWidth, handleHeight),
                                cornerRadius = CornerRadius(handleWidth / 2)
                            )
                        })
                }
            } else {
                when {
                    showSettings -> settings()
                    showPlayer -> player(false)
                    else -> library(false)
                }
            }
        }
    }
}

@Composable
fun EmptyNowPlayingPane() {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(playerString("info_no_file_selected"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
