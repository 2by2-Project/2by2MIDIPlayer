package jp.project2by2.musicplayer.ui.loop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.LoopEditorInitialData
import jp.project2by2.musicplayer.PianoRollData
import jp.project2by2.musicplayer.PianoRollNote
import jp.project2by2.musicplayer.resolveSavedTick
import jp.project2by2.musicplayer.snapToMeasureLine
import jp.project2by2.musicplayer.ui.player.playerString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private const val LOOP_EDITOR_BG = 0xFF161616.toInt()
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 40f
private const val INITIAL_VISIBLE_MEASURES = 5

/** Preview playback of the edited section. Hosts adapt their own BASS player; calls come from the UI thread. */
interface LoopPreviewControl {
    fun setPreviewWindow(loopStartMs: Long?, endMs: Long?)
    fun play(): Boolean
    fun pause()
    fun seekTo(ms: Long)
    fun isPlaying(): Boolean
    fun currentPositionMs(): Long
}

/** Loaded once when the editor opens; [preview] is null when preview playback is unavailable. */
class LoopEditorSource(
    val fileName: String,
    val initial: LoopEditorInitialData,
    val preview: LoopPreviewControl?,
)

/** [SerializationFailed]: the MIDI could not be rewritten (nothing was asked or written). */
enum class LoopExportResult { Saved, Cancelled, Failed, SerializationFailed }

private data class EditLoopUiState(
    val fileName: String,
    val pianoRollData: PianoRollData,
    val currentPositionMs: Long,
    val loopPointMs: Long,
    val endPointMs: Long,
    val zoomLevel: Float,
    val scrollOffsetMs: Long,
    val isSaving: Boolean = false
)

private fun calculateInitialZoomLevel(totalDurationMs: Long, measurePositions: List<Long>): Float {
    if (totalDurationMs <= 0L) return MIN_ZOOM
    if (measurePositions.size < 2) return 10f.coerceIn(MIN_ZOOM, MAX_ZOOM)
    val targetIndex = INITIAL_VISIBLE_MEASURES.coerceAtMost(measurePositions.lastIndex)
    val targetWindowMs = (measurePositions[targetIndex] - measurePositions.first()).coerceAtLeast(1L)
    return (totalDurationMs.toFloat() / targetWindowMs.toFloat()).coerceIn(MIN_ZOOM, MAX_ZOOM)
}

/**
 * Android's loop point editor, shared with iOS: move the loop start (CC#111) and the end on the piano
 * roll, preview the loop, and export a copy of the MIDI file.
 * [load] reads the file and prepares the preview off the main thread; null reports a load failure.
 * [export] builds and writes the copy for (loop tick, end tick); hosts choose the destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopPointEditorScreen(
    key: Any,
    load: suspend () -> LoopEditorSource?,
    export: suspend (fileName: String, loopTick: Int, endTick: Int) -> LoopExportResult,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var state by remember(key) { mutableStateOf<EditLoopUiState?>(null) }
    var previewPlayer by remember(key) { mutableStateOf<LoopPreviewControl?>(null) }
    var previewReady by remember(key) { mutableStateOf(false) }
    var previewIsPlaying by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key) {
        withFrameNanos { }
        val loaded = load()
        if (loaded == null) {
            snackbarHostState.showSnackbar("Failed to load MIDI")
        } else {
            val pianoRollData = loaded.initial.pianoRollData
            state = EditLoopUiState(
                fileName = loaded.fileName,
                pianoRollData = pianoRollData,
                currentPositionMs = 0L,
                loopPointMs = loaded.initial.loopPointMs,
                endPointMs = pianoRollData.totalDurationMs,
                zoomLevel = calculateInitialZoomLevel(pianoRollData.totalDurationMs, pianoRollData.measurePositions),
                scrollOffsetMs = 0L
            )
            previewPlayer = loaded.preview
            previewReady = loaded.preview != null
            previewIsPlaying = false
            if (loaded.preview == null) {
                snackbarHostState.showSnackbar("Preview playback is unavailable")
            }
        }
    }

    LaunchedEffect(state?.loopPointMs, state?.endPointMs, previewReady) {
        val current = state ?: return@LaunchedEffect
        val player = previewPlayer ?: return@LaunchedEffect
        if (!previewReady) return@LaunchedEffect
        player.setPreviewWindow(current.loopPointMs, current.endPointMs)
        if (current.currentPositionMs >= current.endPointMs) {
            val fallback = current.loopPointMs.coerceAtMost((current.endPointMs - 1L).coerceAtLeast(0L))
            player.seekTo(fallback)
            state = current.copy(currentPositionMs = fallback)
        }
    }

    LaunchedEffect(previewReady, previewIsPlaying) {
        val player = previewPlayer ?: return@LaunchedEffect
        while (previewReady) {
            val actualPlaying = player.isPlaying()
            if (previewIsPlaying != actualPlaying) {
                previewIsPlaying = actualPlaying
            }
            if (actualPlaying) {
                state = state?.copy(currentPositionMs = player.currentPositionMs())
                delay(33)
            } else {
                delay(100)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playerString("edit_loop_point_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = playerString("back"))
                    }
                },
                actions = {
                    state?.let { current ->
                        IconButton(onClick = {
                            state = current.copy(zoomLevel = (current.zoomLevel * 1.5f).coerceAtMost(MAX_ZOOM))
                        }) {
                            Icon(Icons.Default.ZoomIn, contentDescription = null)
                        }
                        IconButton(onClick = {
                            val newZoom = (current.zoomLevel / 1.5f).coerceAtLeast(MIN_ZOOM)
                            val viewport = (current.pianoRollData.totalDurationMs / newZoom).toLong().coerceAtLeast(1L)
                            state = current.copy(
                                zoomLevel = newZoom,
                                scrollOffsetMs = current.scrollOffsetMs.coerceIn(
                                    0L,
                                    max(0L, current.pianoRollData.totalDurationMs - viewport)
                                )
                            )
                        }) {
                            Icon(Icons.Default.ZoomOut, contentDescription = null)
                        }
                        IconButton(
                            enabled = !current.isSaving,
                            onClick = {
                                val loopTick = resolveSavedTick(current.loopPointMs, current.pianoRollData)
                                val endTick = resolveSavedTick(current.endPointMs, current.pianoRollData)
                                state = current.copy(isSaving = true)
                                scope.launch {
                                    val exportName = current.fileName.let {
                                        if (it.endsWith(".mid", ignoreCase = true)) it else "$it.mid"
                                    }
                                    val result = export(exportName, loopTick, endTick)
                                    state = state?.copy(isSaving = false)
                                    when (result) {
                                        LoopExportResult.Saved -> onSaved()
                                        LoopExportResult.Failed -> snackbarHostState.showSnackbar("エクスポートに失敗しました")
                                        LoopExportResult.SerializationFailed -> snackbarHostState.showSnackbar("MIDIのシリアライズに失敗しました")
                                        LoopExportResult.Cancelled -> Unit
                                    }
                                }
                            }
                        ) {
                            if (current.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Save, contentDescription = playerString("save"))
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        val current = state
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            Text(
                text = current.fileName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            LoopEditorPianoRoll(
                pianoRollData = current.pianoRollData,
                currentPositionMs = current.currentPositionMs,
                loopPointMs = current.loopPointMs,
                endPointMs = current.endPointMs,
                zoomLevel = current.zoomLevel,
                scrollOffsetMs = current.scrollOffsetMs,
                onScrollChange = { newScroll ->
                    state = state?.copy(scrollOffsetMs = newScroll)
                },
                onZoomChange = { newZoom, focusRatio ->
                    state = state?.let { ui ->
                        val smoothZoom = ui.zoomLevel + (newZoom - ui.zoomLevel) * 0.35f
                        val oldViewport = (ui.pianoRollData.totalDurationMs / ui.zoomLevel).toLong().coerceAtLeast(1L)
                        val focusMs = ui.scrollOffsetMs + (oldViewport * focusRatio).toLong()
                        val newViewport = (ui.pianoRollData.totalDurationMs / smoothZoom).toLong().coerceAtLeast(1L)
                        val newScroll = (focusMs - (newViewport * focusRatio).toLong()).coerceIn(
                            0L,
                            max(0L, ui.pianoRollData.totalDurationMs - newViewport)
                        )
                        ui.copy(zoomLevel = smoothZoom, scrollOffsetMs = newScroll)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            SeekableTimeline(
                currentPositionMs = current.currentPositionMs,
                loopPointMs = current.loopPointMs,
                endPointMs = current.endPointMs,
                totalDurationMs = current.pianoRollData.totalDurationMs,
                zoomLevel = current.zoomLevel,
                scrollOffsetMs = current.scrollOffsetMs,
                onCurrentPositionDrag = { newMs ->
                    state = state?.copy(currentPositionMs = newMs)
                },
                onCurrentPositionScrub = { newMs ->
                    state = state?.copy(currentPositionMs = newMs)
                },
                onLoopPointDrag = { newMs ->
                    state = state?.let { ui -> ui.copy(loopPointMs = clampLoopMs(newMs, ui.endPointMs)) }
                },
                onEndPointDrag = { newMs ->
                    state = state?.let { ui ->
                        ui.copy(endPointMs = clampEndMs(newMs, ui.loopPointMs, ui.pianoRollData.totalDurationMs))
                    }
                },
                onCurrentPositionDragEnd = { newMs ->
                    state = state?.let { ui ->
                        val snapped = snapToMeasureLine(newMs, ui.pianoRollData)
                        if (previewReady) previewPlayer?.seekTo(snapped)
                        ui.copy(currentPositionMs = snapped)
                    }
                },
                onCurrentPositionScrubEnd = { newMs ->
                    state = state?.let { ui ->
                        val snapped = snapToMeasureLine(newMs, ui.pianoRollData)
                        if (previewReady) previewPlayer?.seekTo(snapped)
                        ui.copy(currentPositionMs = snapped)
                    }
                },
                onCurrentPositionDragCancel = {},
                onLoopPointDragEnd = { newMs ->
                    state = state?.let { ui ->
                        val snapped = snapToMeasureLine(newMs, ui.pianoRollData)
                        ui.copy(loopPointMs = clampLoopMs(snapped, ui.endPointMs))
                    }
                },
                onEndPointDragEnd = { newMs ->
                    state = state?.let { ui ->
                        val snapped = snapToMeasureLine(newMs, ui.pianoRollData)
                        ui.copy(endPointMs = clampEndMs(snapped, ui.loopPointMs, ui.pianoRollData.totalDurationMs))
                    }
                },
                modifier = Modifier.fillMaxWidth().height(84.dp)
            )

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Current: ${formatMs(current.currentPositionMs)}", color = Color.Yellow)
                Text("Loop Start: ${formatMs(current.loopPointMs)}", color = Color.Green)
                Text("End Point: ${formatMs(current.endPointMs)}", color = Color.Red)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(
                    onClick = {
                        val player = previewPlayer
                        if (!previewReady || player == null) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Preview playback is unavailable")
                            }
                        } else if (previewIsPlaying) {
                            player.pause()
                            previewIsPlaying = false
                        } else {
                            val startMs = if (current.currentPositionMs >= current.endPointMs) {
                                current.loopPointMs.coerceAtMost((current.endPointMs - 1L).coerceAtLeast(0L))
                            } else {
                                current.currentPositionMs
                            }
                            player.seekTo(startMs)
                            state = state?.copy(currentPositionMs = startMs)
                            previewIsPlaying = player.play()
                            if (!previewIsPlaying) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Failed to start preview playback")
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(64.dp),
                    enabled = previewReady
                ) {
                    Icon(
                        imageVector = if (previewIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Button(
                    onClick = {
                        state = state?.let { ui ->
                            val snapped = snapToMeasureLine(ui.currentPositionMs, ui.pianoRollData)
                            ui.copy(loopPointMs = clampLoopMs(snapped, ui.endPointMs))
                        }
                    }
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Set Loop")
                }

                Button(
                    onClick = {
                        state = state?.let { ui ->
                            val snapped = snapToMeasureLine(ui.currentPositionMs, ui.pianoRollData)
                            ui.copy(endPointMs = clampEndMs(snapped, ui.loopPointMs, ui.pianoRollData.totalDurationMs))
                        }
                    }
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Set End")
                }
            }
        }
    }
}

@Composable
private fun LoopEditorPianoRoll(
    pianoRollData: PianoRollData,
    currentPositionMs: Long,
    loopPointMs: Long,
    endPointMs: Long,
    zoomLevel: Float,
    scrollOffsetMs: Long,
    onScrollChange: (Long) -> Unit,
    onZoomChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestScrollOffsetMs by rememberUpdatedState(scrollOffsetMs)
    val latestZoomLevel by rememberUpdatedState(zoomLevel)
    val latestOnScrollChange by rememberUpdatedState(onScrollChange)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)
    val backgroundColor = Color(LOOP_EDITOR_BG)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = with(LocalDensity.current) {
        TextStyle(color = Color.Gray.copy(alpha = 0.45f), fontSize = 32f.toSp())
    }

    Canvas(
        modifier = modifier
            .background(backgroundColor)
            .pointerInput(pianoRollData.totalDurationMs) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (abs(zoom - 1f) > 0.001f) {
                        val focusRatio = if (size.width > 0f) {
                            (centroid.x / size.width).coerceIn(0f, 1f)
                        } else {
                            0.5f
                        }
                        val newZoom = (latestZoomLevel * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        latestOnZoomChange(newZoom, focusRatio)
                    }

                    if (abs(pan.x) > 0.01f && size.width > 0f) {
                        val viewportDurationMs = (pianoRollData.totalDurationMs / latestZoomLevel).toLong().coerceAtLeast(1L)
                        val dragMs = (-pan.x / size.width * viewportDurationMs).toLong()
                        val newScroll = (latestScrollOffsetMs + dragMs).coerceIn(
                            0L,
                            max(0L, pianoRollData.totalDurationMs - viewportDurationMs)
                        )
                        latestOnScrollChange(newScroll)
                    }
                }
            }
    ) {
        val viewportDurationMs = (pianoRollData.totalDurationMs / zoomLevel).toLong().coerceAtLeast(1L)
        val visibleStartMs = scrollOffsetMs
        val visibleEndMs = (scrollOffsetMs + viewportDurationMs).coerceAtMost(pianoRollData.totalDurationMs)
        drawMsProportionalPianoRoll(
            notes = pianoRollData.notes,
            measurePositionsMs = pianoRollData.measurePositions,
            currentPositionMs = currentPositionMs,
            loopPointMs = loopPointMs,
            endPointMs = endPointMs,
            totalDurationMs = pianoRollData.totalDurationMs,
            visibleStartMs = visibleStartMs,
            visibleEndMs = visibleEndMs,
            textMeasurer = textMeasurer,
            labelStyle = labelStyle,
            backgroundColor = backgroundColor,
            clipAtEndPoint = false
        )
    }
}

private val MidiChannelNeonPalette = listOf(
    Color(0xFFFF5252),
    Color(0xFFFF6E40),
    Color(0xFFFFD740),
    Color(0xFFFFFF00),
    Color(0xFFEEFF41),
    Color(0xFFB2FF59),
    Color(0xFF69F0AE),
    Color(0xFF64FFDA),
    Color(0xFF18FFFF),
    Color(0xFFEEEEEE),
    Color(0xFF40C4FF),
    Color(0xFF448AFF),
    Color(0xFF536DFE),
    Color(0xFF7C4DFF),
    Color(0xFFB388FF),
    Color(0xFFFF4081)
)

/** Notes, measure lines and markers placed in proportion to milliseconds (the editor's timeline). */
private fun DrawScope.drawMsProportionalPianoRoll(
    notes: List<PianoRollNote>,
    measurePositionsMs: List<Long>,
    currentPositionMs: Long,
    loopPointMs: Long,
    endPointMs: Long,
    totalDurationMs: Long,
    visibleStartMs: Long,
    visibleEndMs: Long,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    backgroundColor: Color = Color(0xFF161616),
    clipAtEndPoint: Boolean = true
) {
    val safeTotalMs = totalDurationMs.coerceAtLeast(1L)
    val safeStart = visibleStartMs.coerceIn(0L, (safeTotalMs - 1L).coerceAtLeast(0L))
    val safeEnd = visibleEndMs.coerceIn(safeStart + 1L, safeTotalMs)
    val viewport = (safeEnd - safeStart).coerceAtLeast(1L)
    val drawableEndMs = if (clipAtEndPoint) minOf(safeEnd, endPointMs) else safeEnd
    val endX = ((endPointMs - safeStart).toFloat() / viewport.toFloat()) * size.width

    fun msToX(ms: Long): Float = ((ms - safeStart).toFloat() / viewport.toFloat()) * size.width

    // Measure lines and numbers (baseline 32 px, as Android drew them with a 32 px Paint).
    measurePositionsMs.forEachIndexed { index, measureMs ->
        if (measureMs !in safeStart..drawableEndMs) return@forEachIndexed
        val x = msToX(measureMs)
        drawLine(Color.Gray.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, size.height), 1.5f)
        val label = textMeasurer.measure("${index + 1}", labelStyle)
        drawText(label, topLeft = Offset(x + 10f, 32f - label.firstBaseline))
    }

    notes.forEach { note ->
        if (note.startMs >= drawableEndMs || note.endMs <= safeStart) return@forEach
        val clippedStartMs = maxOf(note.startMs, safeStart)
        val clippedEndMs = minOf(note.endMs, drawableEndMs)
        if (clippedEndMs <= clippedStartMs) return@forEach
        val x = msToX(clippedStartMs)
        val w = msToX(clippedEndMs) - x
        val y = ((127 - note.noteNumber).toFloat() / 127f) * size.height
        val h = size.height / 128f * 2f
        val channelColor = MidiChannelNeonPalette[note.channel.mod(MidiChannelNeonPalette.size)]
        drawRect(
            color = channelColor.copy(alpha = 0.75f),
            topLeft = Offset(x, y),
            size = Size(w.coerceAtLeast(2f), h)
        )
    }

    fun drawMarker(ms: Long, color: Color, strokeWidth: Float) {
        if (ms !in safeStart..safeEnd) return
        if (clipAtEndPoint && ms > endPointMs && color != Color.Red) return
        val x = msToX(ms)
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth)
    }
    drawMarker(endPointMs, Color.Red, 5f)
    drawMarker(loopPointMs, Color.Green, 5f)
    drawMarker(currentPositionMs, Color.White, 5f)

    if (clipAtEndPoint && endX < size.width) {
        drawRect(
            color = backgroundColor,
            topLeft = Offset(endX.coerceAtLeast(0f), 0f),
            size = Size((size.width - endX).coerceAtLeast(0f), size.height)
        )
        if (endX in 0f..size.width) {
            drawLine(Color.Red, Offset(endX, 0f), Offset(endX, size.height), 5f)
        }
    }
}

@Composable
private fun SeekableTimeline(
    currentPositionMs: Long,
    loopPointMs: Long,
    endPointMs: Long,
    totalDurationMs: Long,
    zoomLevel: Float,
    scrollOffsetMs: Long,
    onCurrentPositionDrag: (Long) -> Unit,
    onCurrentPositionScrub: (Long) -> Unit,
    onLoopPointDrag: (Long) -> Unit,
    onEndPointDrag: (Long) -> Unit,
    onCurrentPositionDragEnd: (Long) -> Unit,
    onCurrentPositionScrubEnd: (Long) -> Unit,
    onCurrentPositionDragCancel: () -> Unit,
    onLoopPointDragEnd: (Long) -> Unit,
    onEndPointDragEnd: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.background(Color.Gray.copy(alpha = 0.18f))
    ) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val viewportDurationMs = (totalDurationMs / zoomLevel).toLong().coerceAtLeast(1L)
        val visibleStartMs = scrollOffsetMs
        var scrubX by remember { mutableFloatStateOf(0f) }

        fun msToX(ms: Long): Float {
            return ((ms - visibleStartMs).toFloat() / viewportDurationMs.toFloat()) * maxWidthPx
        }

        fun xToMs(x: Float): Long {
            return (visibleStartMs + (x / maxWidthPx * viewportDurationMs.toFloat()).toLong())
                .coerceIn(0L, totalDurationMs)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(totalDurationMs, zoomLevel, scrollOffsetMs, maxWidthPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            scrubX = offset.x.coerceIn(0f, maxWidthPx)
                            onCurrentPositionScrub(xToMs(scrubX))
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            scrubX = (scrubX + dragAmount.x).coerceIn(0f, maxWidthPx)
                            onCurrentPositionScrub(xToMs(scrubX))
                        },
                        onDragEnd = { onCurrentPositionScrubEnd(xToMs(scrubX)) },
                        onDragCancel = {
                            onCurrentPositionDragCancel()
                            onCurrentPositionScrubEnd(xToMs(scrubX))
                        }
                    )
                }
        )

        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Gray.copy(alpha = 0.2f))
            if (endPointMs in visibleStartMs..(visibleStartMs + viewportDurationMs)) {
                val x = msToX(endPointMs)
                drawLine(Color.Red.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, size.height), 2f)
            }
            if (loopPointMs in visibleStartMs..(visibleStartMs + viewportDurationMs)) {
                val x = msToX(loopPointMs)
                drawLine(Color.Green.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, size.height), 2f)
            }
            if (currentPositionMs in visibleStartMs..(visibleStartMs + viewportDurationMs)) {
                val x = msToX(currentPositionMs)
                drawLine(Color.Yellow.copy(alpha = 0.3f), Offset(x, 0f), Offset(x, size.height), 2f)
            }
        }

        if (endPointMs in visibleStartMs..(visibleStartMs + viewportDurationMs)) {
            DraggableTimelineHandle(
                id = "end",
                position = msToX(endPointMs),
                color = Color.Red,
                maxWidthPx = maxWidthPx,
                onDrag = { onEndPointDrag(xToMs(it)) },
                onDragEnd = { onEndPointDragEnd(xToMs(it)) }
            )
        }

        if (loopPointMs in visibleStartMs..(visibleStartMs + viewportDurationMs)) {
            DraggableTimelineHandle(
                id = "loop",
                position = msToX(loopPointMs),
                color = Color.Green,
                maxWidthPx = maxWidthPx,
                onDrag = { onLoopPointDrag(xToMs(it)) },
                onDragEnd = { onLoopPointDragEnd(xToMs(it)) }
            )
        }

        if (currentPositionMs in visibleStartMs..(visibleStartMs + viewportDurationMs)) {
            DraggableTimelineHandle(
                id = "current",
                position = msToX(currentPositionMs),
                color = Color.Yellow,
                maxWidthPx = maxWidthPx,
                onDrag = { onCurrentPositionDrag(xToMs(it)) },
                onDragEnd = { onCurrentPositionDragEnd(xToMs(it)) },
                onDragCancel = onCurrentPositionDragCancel
            )
        }
    }
}

@Composable
private fun DraggableTimelineHandle(
    id: String,
    position: Float,
    color: Color,
    maxWidthPx: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: ((Float) -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    var isDragging by remember(id) { mutableStateOf(false) }
    var dragX by remember(id) { mutableFloatStateOf(position) }
    val currentX = if (isDragging) dragX else position
    val latestPosition by rememberUpdatedState(position)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnDragCancel by rememberUpdatedState(onDragCancel)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = with(density) { currentX.toDp().roundToPx() - 12.dp.roundToPx() },
                    y = 0
                )
            }
            .size(24.dp)
            .pointerInput(id, maxWidthPx) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        dragX = latestPosition
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragX = (dragX + dragAmount.x).coerceIn(0f, maxWidthPx)
                        latestOnDrag(dragX)
                    },
                    onDragEnd = {
                        isDragging = false
                        latestOnDragEnd?.invoke(dragX)
                    },
                    onDragCancel = {
                        isDragging = false
                        latestOnDragCancel?.invoke()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(color, CircleShape)
        )
    }
}

private fun clampLoopMs(loopMs: Long, endMs: Long): Long {
    return loopMs.coerceIn(0L, (endMs - 1L).coerceAtLeast(0L))
}

private fun clampEndMs(endMs: Long, loopMs: Long, totalDurationMs: Long): Long {
    return endMs.coerceIn((loopMs + 1L).coerceAtMost(totalDurationMs), totalDurationMs)
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centiseconds = (ms % 1000L) / 10L
    return "$minutes:${seconds.toString().padStart(2, '0')}.${centiseconds.toString().padStart(2, '0')}"
}
