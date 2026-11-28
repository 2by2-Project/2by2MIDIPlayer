package jp.project2by2.musicplayer.ui.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable

/**
 * Browse's folder change on phones (Android and iOS): within the same screen, opening a deeper folder
 * slides in from the right and going up slides in from the left, like a file explorer. Other changes
 * (search, switching storage) fade. [depth] counts levels from the storage root.
 */
@Composable
fun <T> BrowseFolderTransition(
    target: T,
    depth: (T) -> Int,
    sameScreen: (T, T) -> Boolean,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            val deeper = depth(targetState) - depth(initialState)
            when {
                sameScreen(targetState, initialState) && deeper != 0 -> {
                    val direction = if (deeper > 0) 1 else -1
                    slideInHorizontally(
                        initialOffsetX = { it * direction },
                        animationSpec = tween(220)
                    ) + fadeIn(animationSpec = tween(120)) togetherWith
                        slideOutHorizontally(
                            targetOffsetX = { -it * direction },
                            animationSpec = tween(220)
                        ) + fadeOut(animationSpec = tween(120))
                }
                else ->
                    fadeIn(animationSpec = tween(120)) togetherWith
                        fadeOut(animationSpec = tween(120))
            }
        },
        label = "BrowseContent"
    ) { content(it) }
}
