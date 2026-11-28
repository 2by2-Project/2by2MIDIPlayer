@file:Suppress("DEPRECATION")

package jp.project2by2.musicplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.PredictiveBackHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Compose's UIKit back gesture (swipe from the left edge). PredictiveBackHandler is deprecated in
 * favour of NavigationEventHandler, but it is the stable surface of the same dispatcher in 1.10.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun PlatformPredictiveBackHandler(enabled: Boolean, onBack: suspend (progress: Flow<Float>) -> Unit) {
    PredictiveBackHandler(enabled) { events -> onBack(events.map { it.progress }) }
}
