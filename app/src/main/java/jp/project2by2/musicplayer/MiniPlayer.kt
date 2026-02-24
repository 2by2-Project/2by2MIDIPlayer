package jp.project2by2.musicplayer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class MiniPlayerUi(
    val title: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val loopStartMs: Long,
    val loopEndMs: Long,
)

// Bottom mini player
@Composable
fun MiniPlayerBar(
    title: String,
    artist: String? = null,
    artworkUri: Uri? = null,
    isPlaying: Boolean,
    progress: Float,
    currentPositionMs: Long,
    loopStartMs: Long,
    loopEndMs: Long,
    onPlayPause: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExpandRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // For seekbar slider
    var isSeeking by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableStateOf(progress) }
    var isDetailsExpanded by remember { mutableStateOf(false) }
    var dragAccumY by remember { mutableStateOf(0f) }

    val loopEnabled by SettingsDataStore.loopEnabledFlow(context).collectAsState(initial = false)
    val shuffleEnabled by SettingsDataStore.shuffleEnabledFlow(context).collectAsState(initial = false)

    Surface(
        shape = RoundedCornerShape(0.dp),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f),
        onClick = { onExpandRequest() }
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork
                val coverBitmap = rememberCoverBitmap(artworkUri)
                Box(
                    modifier = Modifier
                        .requiredSize(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                // Title and artist
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        title,
                        Modifier
                            .padding(end = 16.dp)
                            .clipToBounds()
                            .basicMarquee(iterations = Int.MAX_VALUE),
                        maxLines = 1
                    )
                    if (artist != null) {
                        Text(
                            artist,
                            Modifier
                                .padding(end = 16.dp, top = 4.dp),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                val seconds = currentPositionMs / 1000
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                Text(
                    text = String.format("%d:%02d", minutes, remainingSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 8.dp)
                )
                IconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp).size(48.dp)
                    )
                }
            }
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun MiniPlayerContainer(
    playbackService: PlaybackService?,
    selectedMidiFileUri: Uri?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExpandRequest: () -> Unit,
) {
    val context = LocalContext.current
    // 750ms（まずはここ）: 500〜1000msで調整
    val uiState = produceState<MiniPlayerUi?>(initialValue = null, key1 = playbackService, key2 = selectedMidiFileUri) {
        val service = playbackService ?: run { value = null; return@produceState }

        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            // 取得は Default（CPU寄り）へ
            val snapshot = withContext(Dispatchers.Default) {
                val lp = service.getLoopPoint()
                val duration = service.getDurationMs().coerceAtLeast(0L)
                val loopEnd = lp?.endMs?.takeIf { it > 0L }?.coerceIn(0L, duration) ?: duration
                MiniPlayerUi(
                    title = service.getCurrentTitle() ?: context.getString(R.string.info_no_file_selected),
                    isPlaying = service.isPlaying(),
                    positionMs = service.getCurrentPositionMs(),
                    durationMs = duration,
                    loopStartMs = lp?.startMs ?: 0L,
                    loopEndMs = loopEnd,
                )
            }

            // value 代入（= Compose state更新）は Main に戻った状態で行われる
            value = snapshot

            delay(if (snapshot.isPlaying) 100L else 200L)
        }
    }.value

    if (uiState == null || selectedMidiFileUri == null) return

    // derivedStateOf（局所化）
    val progress by remember(uiState.positionMs, uiState.durationMs) {
        derivedStateOf {
            if (uiState.durationMs > 0) uiState.positionMs.toFloat() / uiState.durationMs.toFloat() else 0f
        }
    }

    MiniPlayerBar(
        title = uiState.title,
        artist = playbackService?.currentArtist,
        artworkUri = playbackService?.currentArtworkUri,
        isPlaying = uiState.isPlaying,
        progress = progress,
        currentPositionMs = uiState.positionMs,
        loopStartMs = uiState.loopStartMs,
        loopEndMs = uiState.loopEndMs,
        onPlayPause = { if (uiState.isPlaying) onPause() else onPlay() },
        onSeekTo = { ratio ->
            val ms = (ratio.coerceIn(0f, 1f) * uiState.durationMs).toLong()
            onSeekToMs(ms)
        },
        onPrevious = onPrevious,
        onNext = onNext,
        onExpandRequest = onExpandRequest,
    )
}
