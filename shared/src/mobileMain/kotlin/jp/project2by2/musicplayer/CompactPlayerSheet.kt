package jp.project2by2.musicplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

// The compact (phone) player: a mini bar above the navigation bar that drags up into the full
// Now Playing sheet. Android and iOS hosts place these around their library Scaffold.

/** On the Box that holds the library; the expanded sheet is drawn over it. */
fun Modifier.playerSheetHost(state: PlayerSheetState): Modifier = onGloballyPositioned {
    state.hostTopInWindow = it.positionInWindow().y
    state.hostHeight = it.size.height.toFloat()
}

/** The mini player in the bottom bar; it rides on the top edge of the rising sheet while it fades. */
@Composable
fun MiniPlayerSheetBar(
    state: PlayerSheetState,
    visible: Boolean,
    onSettle: (expanded: Boolean, velocityPx: Float) -> Unit,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) +
            fadeIn(animationSpec = tween(400)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) +
            fadeOut(animationSpec = tween(400))
    ) {
        PlayerSheetDragArea(
            state = state,
            onSettle = onSettle,
            modifier = Modifier.onGloballyPositioned {
                state.miniTopInWindow = it.positionInWindow().y
            }
        ) {
            Box(
                modifier = Modifier.graphicsLayer {
                    val p = state.progress
                    translationY = -p * state.travelPx
                    alpha = 1f - (p / 0.3f).coerceIn(0f, 1f)
                }
            ) {
                content()
            }
        }
    }
}

/** The full Now Playing sheet over the library while it is open or moving ([expanded] is the host's flag). */
@Composable
fun ExpandedPlayerSheet(
    state: PlayerSheetState,
    visible: Boolean,
    expanded: Boolean,
    onSettle: (expanded: Boolean, velocityPx: Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetVisible by remember(state) { derivedStateOf { state.progress > 0f } }
    if (visible && (sheetVisible || expanded)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(3f)
                .drawBehind { drawRect(Color.Black, alpha = 0.35f * state.progress) }
        ) {
            PlayerSheetDragArea(
                state = state,
                onSettle = onSettle,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, ((1f - state.progress) * state.travelPx).roundToInt()) }
                    .graphicsLayer { alpha = ((state.progress - 0.05f) / 0.3f).coerceIn(0f, 1f) }
            ) {
                content()
            }
        }
    }
}
