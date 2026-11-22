package jp.project2by2.musicplayer

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/**
 * Window-space bounds of a vertical hinge the shared two-pane layout should split along: a
 * half-opened foldable in book posture, or a hinge that physically separates the display.
 * A flat, seamless fold (e.g. Pixel Fold fully open) returns null so the divider stays draggable.
 */
@Composable
fun rememberVerticalFoldInWindow(): Rect? {
    val activity = LocalActivity.current ?: return null
    val folds = remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull { fold ->
                fold.orientation == FoldingFeature.Orientation.VERTICAL &&
                    (fold.isSeparating || fold.state == FoldingFeature.State.HALF_OPENED)
            }?.bounds?.let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
        }
    }
    return folds.collectAsState(initial = null).value
}
