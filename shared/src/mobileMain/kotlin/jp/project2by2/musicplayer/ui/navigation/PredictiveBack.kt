package jp.project2by2.musicplayer.ui.navigation

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The system back gesture with its progress (0..1): Android's predictive back and iOS's swipe from
 * the left edge. [onBack] collects the progress; the flow completes when the gesture is committed and
 * is cancelled (CancellationException) when the user lets go early. A back key/button commits at once.
 * When several handlers are enabled, the most recently added one receives the gesture.
 */
@Composable
expect fun PlatformPredictiveBackHandler(enabled: Boolean, onBack: suspend (progress: Flow<Float>) -> Unit)

/** Where a back gesture is: [progress] follows the finger while [active]; afterwards it keeps its last value. */
@Stable
class PredictiveBackState {
    var progress by mutableFloatStateOf(0f)
        internal set
    var active by mutableStateOf(false)
        internal set

    /** The preview amount for UI that only follows the gesture (0 once it is committed or cancelled). */
    val previewProgress: Float get() = if (active) progress else 0f

    /** Forgets a committed gesture's last progress, e.g. when a closed page opens again. */
    fun reset() {
        progress = 0f
        active = false
    }
}

/**
 * M3 predictive back for one destination: screens draw their preview from the returned state and
 * [onBack] commits. Letting go early springs the progress back to 0 before the state turns inactive.
 */
@Composable
fun rememberPredictiveBack(enabled: Boolean, onBack: () -> Unit): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val latestOnBack by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    PlatformPredictiveBackHandler(enabled) { events ->
        state.progress = 0f
        state.active = true
        try {
            events.collect { state.progress = it.coerceIn(0f, 1f) }
        } catch (cancelled: CancellationException) {
            // The gesture was abandoned: return smoothly, then let screens leave preview mode.
            scope.launch {
                animate(state.progress, 0f, animationSpec = spring()) { value, _ -> state.progress = value }
                state.active = false
            }
            throw cancelled
        }
        state.active = false
        latestOnBack()
    }
    return state
}
