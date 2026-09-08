package jp.project2by2.musicplayer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val seconds = currentPositionMs / 1000
    MiniPlayerContent(
        title = title,
        artist = artist,
        coverBitmap = rememberCoverBitmap(artworkUri),
        isPlaying = isPlaying,
        progress = progress,
        positionLabel = String.format("%d:%02d", seconds / 60, seconds % 60),
        onPlayPause = onPlayPause,
        onExpandRequest = onExpandRequest,
    )
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
