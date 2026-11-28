package jp.project2by2.musicplayer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One continuous position for the compact player: 0 is the mini player resting above the
 * navigation bar and 1 is the full Now Playing screen. The mini bar and the full screen both
 * read this value, so a drag from either end moves the same sheet under the finger.
 */
@Stable
class PlayerSheetState internal constructor(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set

    /** Where the sheet is heading; the host keeps its expanded flag in step with this. */
    var targetExpanded by mutableStateOf(false)
        private set

    var isDragging by mutableStateOf(false)
        private set

    /** Window Y of the host that draws the expanded sheet. */
    var hostTopInWindow by mutableFloatStateOf(0f)
    var hostHeight by mutableFloatStateOf(0f)

    /** Window Y of the mini player's top edge; the collapsed sheet starts here. */
    var miniTopInWindow by mutableFloatStateOf(Float.NaN)

    /** Distance the sheet top travels between collapsed and expanded. */
    val travelPx: Float
        get() {
            val measured = miniTopInWindow - hostTopInWindow
            return if (measured.isFinite() && measured > 0f) measured else hostHeight
        }

    private var animationJob: Job? = null
    private val velocityTracker = VelocityTracker()
    private var dragTotal = 0f

    /** Records the touch-down so a short, quick swipe still has enough samples for its speed. */
    internal fun press(uptimeMillis: Long) {
        velocityTracker.resetTracking()
        dragTotal = 0f
        velocityTracker.addPosition(uptimeMillis, Offset.Zero)
    }

    internal fun startDrag() {
        animationJob?.cancel()
        isDragging = true
    }

    internal fun drag(uptimeMillis: Long, dy: Float) {
        // Track the accumulated finger travel rather than the local position: the sheet
        // under the finger moves, so its local coordinates do not describe the gesture.
        dragTotal += dy
        velocityTracker.addPosition(uptimeMillis, Offset(0f, dragTotal))
        val travel = travelPx
        if (travel > 0f) progress = (progress - dy / travel).coerceIn(0f, 1f)
    }

    /** Ends a drag and returns the chosen state with the release velocity (px/s, + is down). */
    internal fun endDrag(flingThresholdPx: Float): Pair<Boolean, Float> {
        isDragging = false
        val velocity = velocityTracker.calculateVelocity().y
        val expand = when {
            velocity < -flingThresholdPx -> true
            velocity > flingThresholdPx -> false
            // A slow release keeps going the way it was already heading once it has left
            // its resting end, so a deliberate short pull is not undone.
            targetExpanded -> progress > 0.85f
            else -> progress > 0.15f
        }
        return expand to velocity
    }

    /**
     * Back gesture preview (M3 predictive back): the open sheet follows the gesture down part of the
     * way. Committing animates on from here with [animateTo]; cancelling springs back the same way.
     */
    fun previewBack(gestureProgress: Float) {
        animationJob?.cancel()
        progress = 1f - BACK_PREVIEW_TRAVEL * gestureProgress.coerceIn(0f, 1f)
    }

    fun animateTo(expanded: Boolean, velocityPx: Float = 0f) {
        animationJob?.cancel()
        targetExpanded = expanded
        val target = if (expanded) 1f else 0f
        val travel = travelPx
        // Hand the finger's speed to the spring so the release carries on without a hitch.
        val initialVelocity = if (travel > 0f) -velocityPx / travel else 0f
        animationJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                initialVelocity = initialVelocity,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { value, _ -> progress = value.coerceIn(0f, 1f) }
        }
    }
}

/** Part of the sheet's travel a full back gesture previews before the user lets go. */
private const val BACK_PREVIEW_TRAVEL = 0.35f

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlayerSheetState(scope) }
}

/** Vertical drags anywhere in [content] move the sheet; [onSettle] receives the release result. */
@Composable
fun PlayerSheetDragArea(
    state: PlayerSheetState,
    onSettle: (expanded: Boolean, velocityPx: Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.pointerInput(state) {
            val flingThresholdPx = 125.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                state.press(down.uptimeMillis)
                var slopDistance = 0f
                val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                    change.consume()
                    // Move by the whole distance from the touch-down, not just past the slop,
                    // so the sheet stays exactly under the finger.
                    slopDistance = change.position.y - down.position.y
                } ?: return@awaitEachGesture
                state.startDrag()
                state.drag(start.uptimeMillis, slopDistance)
                verticalDrag(start.id) { change ->
                    // Read the movement before consuming: a consumed change reports zero.
                    val dy = change.positionChange().y
                    change.consume()
                    state.drag(change.uptimeMillis, dy)
                }
                val (expanded, velocity) = state.endDrag(flingThresholdPx)
                onSettle(expanded, velocity)
            }
        }
    ) {
        content()
    }
}
