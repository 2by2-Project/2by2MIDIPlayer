@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package jp.project2by2.musicplayer.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import jp.project2by2.musicplayer.*
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.*
import jp.project2by2.musicplayer.ui.player.playerString
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults

@Composable
fun NowPlayingSheet(
    title: String, artist: String, coverBitmap: ImageBitmap? = null,
    ui: NowPlayingState?, pianoRollData: PianoRollData?, loopEnabled: Boolean,
    shuffleEnabled: Boolean, onLoopChange: (Boolean) -> Unit, onShuffleChange: (Boolean) -> Unit,
    showActions: Boolean = false, onActionsClick: () -> Unit = {},
    onSeekToMs: (Long) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onPlayPause: () -> Unit,
    trackKey: Any = title,
    onClose: (() -> Unit)? = null
) {
    var stablePositionMs by remember(trackKey) { mutableLongStateOf(0L) }
    var targetPositionMs by remember(trackKey) { mutableLongStateOf(0L) }
    LaunchedEffect(ui?.positionMs, ui?.durationMs) {
        val raw = ui?.positionMs ?: return@LaunchedEffect
        if (targetPositionMs == 0L && stablePositionMs == 0L) {
            targetPositionMs = raw
            stablePositionMs = raw
            return@LaunchedEffect
        }
        val deltaFromStable = raw - stablePositionMs
        if (deltaFromStable < -250L) {
            // Loop wrap / backward seek: snap immediately.
            stablePositionMs = raw
        }
        targetPositionMs = raw
    }

    // Keep the frame clock running across audio position updates; restarting skips a frame.
    // Read targetPositionMs inside the loop so rendering follows the display refresh rate.
    LaunchedEffect(trackKey, ui?.isPlaying, ui?.durationMs, ui?.loopStartMs, ui?.loopEndMs) {
        var lastFrameNanos = 0L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            withFrameNanos { now ->
                if (lastFrameNanos == 0L) {
                    lastFrameNanos = now
                    return@withFrameNanos
                }
                val frameMs = ((now - lastFrameNanos) / 1_000_000L).coerceIn(0L, 50L)
                lastFrameNanos = now
                val duration = ui?.durationMs?.coerceAtLeast(0L) ?: 0L
                val loopStart = ui?.loopStartMs?.coerceAtLeast(0L) ?: 0L
                val loopEnd = ui?.loopEndMs?.coerceAtLeast(loopStart) ?: duration
                if (ui?.isPlaying == true) {
                    var predicted = (stablePositionMs + frameMs).coerceAtMost(duration)
                    if (loopEnd > loopStart && predicted >= loopEnd) {
                        predicted = loopStart + (predicted - loopEnd)
                    }
                    val target = targetPositionMs.coerceIn(0L, duration)
                    val correction = ((target - predicted) * 0.20f).toLong()
                    stablePositionMs = (predicted + correction).coerceIn(0L, duration)
                } else {
                    stablePositionMs = targetPositionMs.coerceIn(0L, duration)
                }
            }
        }
    }

    // For seekbar slider
    var isSeeking by remember(trackKey) { mutableStateOf(false) }
    var sliderValue by remember(trackKey) { mutableStateOf(0.0f) }
    val progress by remember(ui?.positionMs, ui?.durationMs) {
        derivedStateOf {
            val duration = ui?.durationMs ?: 0L
            val position = ui?.positionMs ?: 0L
            if (duration > 0L) {
                (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                
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
                        text = title,
                        Modifier
                            .padding(end = 16.dp)
                            .clipToBounds()
                            .basicMarquee(iterations = Int.MAX_VALUE),
                        maxLines = 1,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = artist,
                        Modifier
                            .padding(end = 16.dp)
                            .clipToBounds()
                            .basicMarquee(iterations = Int.MAX_VALUE),
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (onClose != null) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, playerString("back")) }
                }
                if (showActions) {
                    IconButton(onClick = onActionsClick) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = null,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
            when {
                pianoRollData == null || ui == null -> {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    PlaybackPianoRollView(
                        notes = pianoRollData.notes,
                        measureTickPositions = pianoRollData.measureTickPositions,
                        tickTimeAnchors = pianoRollData.tickTimeAnchors,
                        currentPositionMs = stablePositionMs,
                        loopPointMs = ui.loopStartMs,
                        endPointMs = ui.loopEndMs,
                        totalDurationMs = maxOf(pianoRollData.totalDurationMs, ui.durationMs),
                        totalTicks = pianoRollData.totalTicks,
                        zoomLevel = calculatePlaybackInitialZoomLevel(
                            totalTicks = pianoRollData.totalTicks,
                            measureTickPositions = pianoRollData.measureTickPositions
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Slider(
                    value = if (isSeeking) sliderValue else progress,
                    valueRange = 0f..1f,
                    onValueChange = { v ->
                        isSeeking = true
                        sliderValue = v
                    },
                    onValueChangeFinished = {
                        isSeeking = false
                        val duration = ui?.durationMs ?: 0L
                        val ms = (sliderValue.coerceIn(0f, 1f) * duration.toFloat()).toLong()
                        targetPositionMs = ms
                        onSeekToMs(ms)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 0.dp),
            ) {
                val seconds = (ui?.positionMs ?: 0L) / 1000
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                Text(
                    text = formatPlayerDuration(ui?.positionMs ?: 0L),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
                val durationText = ui?.durationMs ?: 0L
                Text(
                    text = formatPlayerDuration(durationText),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row (
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sideControlButtonSize = 48.dp
                val sideControlIconSize = 32.dp
                IconButton(
                    modifier = Modifier.size(sideControlButtonSize),
                    onClick = {
                        onShuffleChange(!shuffleEnabled)
                    }
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (shuffleEnabled) { MaterialTheme.colorScheme.primaryContainer } else { Color.Transparent },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = null,
                                modifier = Modifier.size(sideControlIconSize)
                            )
                        }
                    }
                }
                IconButton(
                    modifier = Modifier.size(sideControlButtonSize),
                    onClick = onPrevious
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = null,
                        modifier = Modifier.size(sideControlIconSize)
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .size(92.dp)
                ) {
                    IconButton(
                        onClick = {
                            onPlayPause()
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = if (ui?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }
                IconButton(
                    modifier = Modifier.size(sideControlButtonSize),
                    onClick = onNext
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(sideControlIconSize)
                    )
                }
                IconButton(
                    modifier = Modifier.size(sideControlButtonSize),
                    onClick = {
                        onLoopChange(!loopEnabled)
                    }
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (loopEnabled) { MaterialTheme.colorScheme.primaryContainer } else { Color.Transparent },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Default.Repeat,
                                contentDescription = null,
                                modifier = Modifier.size(sideControlIconSize)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun calculatePlaybackInitialZoomLevel(
    totalTicks: Int,
    measureTickPositions: List<Int>,
    targetVisibleMeasures: Int = 5
): Float {
    if (totalTicks <= 0) return 10f
    val spans = measureTickPositions
        .zipWithNext()
        .map { (a, b) -> (b - a).coerceAtLeast(1) }
    if (spans.isEmpty()) return 10f.coerceIn(1f, 40f)

    val dominantMeasureTicks = spans
        .groupingBy { it }
        .eachCount()
        .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        ?.key
        ?: spans[spans.size / 2]

    val targetWindowTicks = (dominantMeasureTicks * targetVisibleMeasures).coerceAtLeast(1)
    val zoom = (totalTicks.toFloat() / targetWindowTicks.toFloat()).coerceIn(1f, 40f)
    return zoom
}
fun formatPlayerDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

