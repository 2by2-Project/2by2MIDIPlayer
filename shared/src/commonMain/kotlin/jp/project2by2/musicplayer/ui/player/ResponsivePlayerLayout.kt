package jp.project2by2.musicplayer.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset

/** Only placement changes; hosts supply the same screen Composables at both sizes. */
@Composable
fun ResponsivePlayerLayout(
    showSettings: Boolean,
    showPlayer: Boolean,
    onBack: (wide: Boolean) -> Unit,
    library: @Composable (wide: Boolean) -> Unit,
    settings: @Composable () -> Unit,
    player: @Composable (wide: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dividerModifier: Modifier = Modifier
) {
    var splitFraction by rememberSaveable { mutableStateOf<Float?>(null) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 1000.dp
        val availableWidth = maxWidth
        val libraryWidth = (splitFraction?.let { availableWidth * it }
            ?: (maxWidth * 0.4f).coerceIn(360.dp, 480.dp))
            .coerceIn(360.dp, (availableWidth - 360.dp).coerceAtLeast(360.dp))
        Box(Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onBack(wide); true } else false
        }) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(libraryWidth).fillMaxHeight()) { library(true) }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (showSettings) settings() else player(true)
                    }
                }
                val dividerColor = MaterialTheme.colorScheme.outlineVariant
                // Overlay the panes: no visible gutter, with four physical pixels on each side.
                Box(dividerModifier
                    .offset { IntOffset(with(density) { libraryWidth.roundToPx() } - 4, 0) }
                    .width(with(density) { 8.toDp() }).fillMaxHeight()
                    .horizontalResizePointer()
                    .drawBehind { drawRect(dividerColor, Offset(4f, 0f), Size(1f, size.height)) }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            val currentWidth = splitFraction?.let { availableWidth * it } ?: libraryWidth
                            val nextWidth = (currentWidth.coerceIn(360.dp, availableWidth - 360.dp) + with(density) { delta.toDp() })
                                .coerceIn(360.dp, availableWidth - 360.dp)
                            splitFraction = nextWidth / availableWidth
                        }
                    ))
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
