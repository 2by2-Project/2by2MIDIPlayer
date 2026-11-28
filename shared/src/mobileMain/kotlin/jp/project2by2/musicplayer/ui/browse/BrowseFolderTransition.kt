package jp.project2by2.musicplayer.ui.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import jp.project2by2.musicplayer.ui.navigation.PredictiveBackState

/**
 * Browse's folder change on phones (Android and iOS): within the same screen, opening a deeper folder
 * slides in from the right and going up slides in from the left, like a file explorer. Other changes
 * (search, switching storage) fade. [depth] counts levels from the storage root.
 *
 * With [backTarget] and [back], the system back gesture scrubs the same slide (M3 predictive back):
 * the open folder moves right while [backTarget] comes in from the left, and committing finishes it.
 */
@Composable
fun <T> BrowseFolderTransition(
    target: T,
    depth: (T) -> Int,
    sameScreen: (T, T) -> Boolean,
    backTarget: T? = null,
    back: PredictiveBackState? = null,
    content: @Composable (T) -> Unit,
) {
    val seekable = remember { SeekableTransitionState(target) }
    // Ordinary navigation (and a committed gesture, which continues from where the finger left it).
    LaunchedEffect(target) {
        if (seekable.currentState != target || seekable.targetState != target) seekable.animateTo(target)
    }
    // The gesture is read here only (not during composition), so following the finger only advances
    // the transition; the pages themselves are not recomposed.
    LaunchedEffect(back, backTarget, target) {
        if (back == null) return@LaunchedEffect
        snapshotFlow { back.active to back.progress }.collectLatest { (active, progress) ->
            when {
                active && backTarget != null && backTarget != target -> seekable.seekTo(progress, backTarget)
                // A cancelled gesture has returned to 0: leave the preview without moving anything. (A
                // committed one keeps its progress until the host switches to the new folder.)
                !active && progress == 0f && seekable.targetState != target && seekable.currentState == target ->
                    seekable.snapTo(target)
            }
        }
    }
    rememberTransition(seekable, label = "BrowseContent").AnimatedContent(
        transitionSpec = {
            val deeper = depth(targetState) - depth(initialState)
            // Following the back gesture, the pages move in proportion to the finger: no easing, and the
            // fade lasts as long as the slide. Taps keep the eased explorer animation.
            val following = back?.active == true
            val slide = if (following) tween<IntOffset>(220, easing = LinearEasing) else tween(220)
            val fade = if (following) tween<Float>(220, easing = LinearEasing) else tween(120)
            when {
                sameScreen(targetState, initialState) && deeper != 0 -> {
                    val direction = if (deeper > 0) 1 else -1
                    slideInHorizontally(
                        initialOffsetX = { it * direction },
                        animationSpec = slide
                    ) + fadeIn(animationSpec = fade) togetherWith
                        slideOutHorizontally(
                            targetOffsetX = { -it * direction },
                            animationSpec = slide
                        ) + fadeOut(animationSpec = fade)
                }
                else ->
                    fadeIn(animationSpec = fade) togetherWith
                        fadeOut(animationSpec = fade)
            }
        },
    ) { content(it) }
}
