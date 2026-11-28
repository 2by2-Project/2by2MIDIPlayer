package jp.project2by2.musicplayer.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The Activity's OnBackPressedDispatcher (predictive back on Android 13+, the back key elsewhere). */
@Composable
actual fun PlatformPredictiveBackHandler(enabled: Boolean, onBack: suspend (progress: Flow<Float>) -> Unit) {
    PredictiveBackHandler(enabled) { events -> onBack(events.map { it.progress }) }
}
