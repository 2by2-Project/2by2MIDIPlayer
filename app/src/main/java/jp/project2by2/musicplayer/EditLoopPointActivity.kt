package jp.project2by2.musicplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import dev.atsushieno.ktmidi.Midi1CompoundMessage
import dev.atsushieno.ktmidi.Midi1Event
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.Midi1SimpleMessage
import dev.atsushieno.ktmidi.MidiChannelStatus
import dev.atsushieno.ktmidi.read
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

private const val LOOP_EDITOR_BG = 0xFF161616.toInt()
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 40f
private const val INITIAL_VISIBLE_MEASURES = 5

@UnstableApi
class EditLoopPointActivity : ComponentActivity() {
    private var isServiceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            (service as? PlaybackService.LocalBinder)?.getService()?.pause()
        }
        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isServiceBound = bindService(
            Intent(this, PlaybackService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        val uri = readUriExtra() ?: run {
            finish()
            return
        }

        setContent {
            _2by2MusicPlayerTheme {
                EditLoopPointScreen(
                    fileUri = uri,
                    onBack = { finish() },
                    onSaved = {
                        setResult(RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun readUriExtra(): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_URI)
        }
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
    }
}

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

private data class SourceFileInfo(val displayName: String)

private fun calculateInitialZoomLevel(totalDurationMs: Long, measurePositions: List<Long>): Float {
    if (totalDurationMs <= 0L) return MIN_ZOOM
    if (measurePositions.size < 2) return 10f.coerceIn(MIN_ZOOM, MAX_ZOOM)
    val targetIndex = INITIAL_VISIBLE_MEASURES.coerceAtMost(measurePositions.lastIndex)
    val targetWindowMs = (measurePositions[targetIndex] - measurePositions.first()).coerceAtLeast(1L)
    return (totalDurationMs.toFloat() / targetWindowMs.toFloat()).coerceIn(MIN_ZOOM, MAX_ZOOM)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditLoopPointScreen(
    fileUri: Uri,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val previewPlayer = remember(fileUri) { LoopPreviewPlayer(context.applicationContext) }
    var state by remember(fileUri) { mutableStateOf<EditLoopUiState?>(null) }
    var sourceFileInfo by remember(fileUri) { mutableStateOf<SourceFileInfo?>(null) }
    var previewReady by remember(fileUri) { mutableStateOf(false) }
    var previewIsPlaying by remember(fileUri) { mutableStateOf(false) }
    var pendingExportBytes by remember(fileUri) { mutableStateOf<ByteArray?>(null) }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (uri != null && bytes != null) {
            state = state?.copy(isSaving = true)
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        val currentName = resolveDisplayName(context, uri)
                        val writeUri = if (!currentName.endsWith(".mid", ignoreCase = true)) {
                            val newName = if ('.' in currentName) {
                                currentName.substringBeforeLast('.') + ".mid"
                            } else {
                                "$currentName.mid"
                            }
                            DocumentsContract.renameDocument(context.contentResolver, uri, newName) ?: uri
                        } else {
                            uri
                        }
                        context.contentResolver.openOutputStream(writeUri)?.use { it.write(bytes) }
                        true
                    }.getOrElse {
                        Log.e("EditLoopPoint", "SAF export failed", it)
                        false
                    }
                }
                state = state?.copy(isSaving = false)
                if (success) onSaved()
                else snackbarHostState.showSnackbar("エクスポートに失敗しました")
            }
        } else {
            state = state?.copy(isSaving = false)
        }
    }

    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
        }
    }

    LaunchedEffect(fileUri) {
        withFrameNanos { }
        val loaded = withContext(Dispatchers.IO) {
            val initialData = loadLoopEditorInitialDataFast(context, fileUri) ?: return@withContext null
            val info = resolveSourceFileInfo(context, fileUri)
            val pianoRollData = initialData.pianoRollData
            val uiState = EditLoopUiState(
                fileName = info.displayName,
                pianoRollData = pianoRollData,
                currentPositionMs = 0L,
                loopPointMs = initialData.loopPointMs,
                endPointMs = pianoRollData.totalDurationMs,
                zoomLevel = calculateInitialZoomLevel(pianoRollData.totalDurationMs, pianoRollData.measurePositions),
                scrollOffsetMs = 0L
            )
            val previewLoaded = previewPlayer.load(
                uri = fileUri,
                previewWindow = PreviewWindow(
                    loopStartMs = uiState.loopPointMs,
                    endMs = uiState.endPointMs
                )
            )
            Triple(info, uiState, previewLoaded)
        }
        if (loaded == null) {
            snackbarHostState.showSnackbar("Failed to load MIDI")
        } else {
            sourceFileInfo = loaded.first
            state = loaded.second
            previewReady = loaded.third
            previewIsPlaying = false
            if (!loaded.third) {
                snackbarHostState.showSnackbar("Preview playback is unavailable")
            }
        }
    }

    LaunchedEffect(state?.loopPointMs, state?.endPointMs, previewReady) {
        val current = state ?: return@LaunchedEffect
        if (!previewReady) return@LaunchedEffect
        previewPlayer.setPreviewWindow(current.loopPointMs, current.endPointMs)
        if (current.currentPositionMs >= current.endPointMs) {
            val fallback = current.loopPointMs.coerceAtMost((current.endPointMs - 1L).coerceAtLeast(0L))
            previewPlayer.seekTo(fallback)
            state = current.copy(currentPositionMs = fallback)
        }
    }

    LaunchedEffect(previewReady, previewIsPlaying) {
        while (previewReady) {
            val actualPlaying = previewPlayer.isPlaying()
            if (previewIsPlaying != actualPlaying) {
                previewIsPlaying = actualPlaying
            }
            if (actualPlaying) {
                state = state?.copy(currentPositionMs = previewPlayer.getCurrentPositionMs())
                delay(33)
            } else {
                delay(100)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_loop_point_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                                val info = sourceFileInfo ?: return@IconButton
                                val loopTick = resolveSavedTick(current.loopPointMs, current.pianoRollData)
                                val endTick = resolveSavedTick(current.endPointMs, current.pianoRollData)
                                state = current.copy(isSaving = true)
                                scope.launch {
                                    val bytes = withContext(Dispatchers.IO) {
                                        buildModifiedMidiBytes(context, fileUri, loopTick, endTick)
                                    }
                                    state = state?.copy(isSaving = false)
                                    if (bytes != null) {
                                        pendingExportBytes = bytes
                                        val exportName = info.displayName.let {
                                            if (it.endsWith(".mid", ignoreCase = true)) it else "$it.mid"
                                        }
                                        createDocumentLauncher.launch(exportName)
                                    } else {
                                        snackbarHostState.showSnackbar("MIDIのシリアライズに失敗しました")
                                    }
                                }
                            }
                        ) {
                            if (current.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
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
                        if (previewReady) previewPlayer.seekTo(snapped)
                        ui.copy(currentPositionMs = snapped)
                    }
                },
                onCurrentPositionScrubEnd = { newMs ->
                    state = state?.let { ui ->
                        val snapped = snapToMeasureLine(newMs, ui.pianoRollData)
                        if (previewReady) previewPlayer.seekTo(snapped)
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
                        if (!previewReady) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Preview playback is unavailable")
                            }
                        } else if (previewIsPlaying) {
                            previewPlayer.pause()
                            previewIsPlaying = false
                        } else {
                            val startMs = if (current.currentPositionMs >= current.endPointMs) {
                                current.loopPointMs.coerceAtMost((current.endPointMs - 1L).coerceAtLeast(0L))
                            } else {
                                current.currentPositionMs
                            }
                            previewPlayer.seekTo(startMs)
                            state = state?.copy(currentPositionMs = startMs)
                            previewIsPlaying = previewPlayer.play()
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
            backgroundColor = backgroundColor,
            clipAtEndPoint = false
        )
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
    return "%d:%02d.%02d".format(minutes, seconds, centiseconds)
}

fun snapToMeasureLine(targetMs: Long, pianoRollData: PianoRollData): Long {
    val nearestTick = resolveSavedTick(targetMs, pianoRollData)
    return tickToMsFast(nearestTick, pianoRollData.tickTimeAnchors)
}

private fun buildQuarterMeasureSnapTicks(pianoRollData: PianoRollData): List<Int> {
    val measureTicks = pianoRollData.measureTickPositions
    if (measureTicks.isEmpty()) return emptyList()
    val snapTicks = linkedSetOf<Int>()
    for ((index, measureStartTick) in measureTicks.withIndex()) {
        val measureEndTick = if (index + 1 < measureTicks.size) {
            measureTicks[index + 1]
        } else {
            pianoRollData.totalTicks.coerceAtLeast(measureStartTick + 1)
        }
        val measureLength = (measureEndTick - measureStartTick).coerceAtLeast(1)
        for (step in 0..3) {
            val tick = measureStartTick + ((measureLength * step) / 4.0).toInt()
            snapTicks.add(tick.coerceIn(0, pianoRollData.totalTicks))
        }
    }
    snapTicks.add(pianoRollData.totalTicks)
    return snapTicks.toList()
}

private fun resolveSavedTick(targetMs: Long, pianoRollData: PianoRollData): Int {
    if (targetMs >= pianoRollData.totalDurationMs) {
        return pianoRollData.totalTicks
    }
    val snapTicks = buildQuarterMeasureSnapTicks(pianoRollData)
    if (snapTicks.isEmpty()) return msToTick(targetMs, pianoRollData.tickTimeAnchors, pianoRollData.totalTicks)
    val targetTick = msToTick(targetMs, pianoRollData.tickTimeAnchors, pianoRollData.totalTicks)
    return snapTicks.minByOrNull { abs(it - targetTick) } ?: targetTick
}

fun approximateTickFromMs(targetMs: Long, music: Midi1Music): Int {
    val maxTick = music.tracks.maxOfOrNull { track -> track.events.sumOf { it.deltaTime } } ?: 0
    var low = 0
    var high = maxTick
    while (low < high) {
        val mid = (low + high) / 2
        val midMs = music.getTimePositionInMillisecondsForTick(mid).toLong()
        if (midMs < targetMs) low = mid + 1 else high = mid
    }
    return low
}

fun findLoopPointFromMusic(music: Midi1Music): Long? {
    val tickMsCache = HashMap<Int, Long>(512)
    for (track in music.tracks) {
        var tick = 0
        for (event in track.events) {
            tick += event.deltaTime
            val message = event.message
            val isCc = ((message.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC)
            if (isCc && message.msb.toInt() == 111) {
                return tickMsCache.getOrPut(tick) {
                    music.getTimePositionInMillisecondsForTick(tick).toLong()
                }
            }
        }
    }
    return null
}

private suspend fun buildModifiedMidiBytes(
    context: Context,
    sourceUri: Uri,
    loopTick: Int,
    endTick: Int
): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val bytes = context.contentResolver.openInputStream(sourceUri)
            ?.use { it.readBytes().asList() } ?: return@withContext null
        val music = Midi1Music().apply { read(bytes) }
        val maxTick = music.tracks.maxOfOrNull { track -> track.events.sumOf { it.deltaTime } } ?: 0
        val newLoopTick = loopTick.coerceIn(0, maxTick)
        val newEndTick = endTick.coerceIn((newLoopTick + 1).coerceAtMost(maxTick), maxTick)

        for (track in music.tracks) {
            track.events.removeAll { event ->
                val message = event.message
                ((message.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC) && message.msb.toInt() == 111
            }
        }

        for (track in music.tracks) {
            var accumulatedTick = 0
            val keptEvents = mutableListOf<Midi1Event>()
            val trailingMetaOrSysEx = mutableListOf<Midi1Event>()
            for (event in track.events) {
                val message = event.message
                val status = message.statusByte.toInt() and 0xFF
                val msb = message.msb.toInt() and 0xFF
                val isMetaEvent = status == 0xFF && msb != 0x2F
                val isSysEx = status == 0xF0

                accumulatedTick += event.deltaTime
                if (accumulatedTick <= newEndTick) {
                    keptEvents.add(event)
                } else if (isMetaEvent || isSysEx) {
                    trailingMetaOrSysEx.add(Midi1Event(0, message))
                } else {
                    break
                }
            }

            keptEvents.addAll(trailingMetaOrSysEx)
            if (keptEvents.isEmpty() || !isEndOfTrack(keptEvents.last())) {
                val lastTick = keptEvents.sumOf { it.deltaTime }
                val endDelta = (newEndTick - lastTick).coerceAtLeast(0)
                keptEvents.add(createEndOfTrackEvent(endDelta))
            }

            track.events.clear()
            track.events.addAll(keptEvents)
        }

        if (music.tracks.isNotEmpty()) {
            insertCCEvent(music.tracks[0], newLoopTick, 111, 0)
        }

        runCatching { writeMidiToBytes(music) }.getOrNull()
    } catch (e: Exception) {
        Log.e("EditLoopPointSave", "Failed to build MIDI bytes", e)
        null
    }
}

private fun resolveSourceFileInfo(context: Context, uri: Uri): SourceFileInfo {
    return SourceFileInfo(displayName = resolveDisplayName(context, uri).ifBlank { "Unknown.mid" })
}

fun isEndOfTrack(event: Midi1Event): Boolean {
    val message = event.message
    return (message.statusByte.toInt() and 0xFF) == 0xFF && (message.msb.toInt() and 0xFF) == 0x2F
}

fun createEndOfTrackEvent(deltaTime: Int): Midi1Event {
    return Midi1Event(deltaTime, Midi1CompoundMessage(0xFF, 0x2F, 0, byteArrayOf()))
}

fun insertCCEvent(track: dev.atsushieno.ktmidi.Midi1Track, targetTick: Int, ccNumber: Int, value: Int) {
    var accumulatedTick = 0
    var insertIndex = 0
    for ((index, event) in track.events.withIndex()) {
        if (accumulatedTick + event.deltaTime > targetTick) {
            insertIndex = index
            break
        }
        accumulatedTick += event.deltaTime
        insertIndex = index + 1
    }

    val deltaTime = targetTick - accumulatedTick
    val ccEvent = Midi1Event(
        deltaTime,
        Midi1SimpleMessage(MidiChannelStatus.CC or 0, ccNumber, value)
    )
    track.events.add(insertIndex, ccEvent)

    if (insertIndex + 1 < track.events.size) {
        val next = track.events[insertIndex + 1]
        val adjustedDelta = (next.deltaTime - deltaTime).coerceAtLeast(0)
        track.events[insertIndex + 1] = Midi1Event(adjustedDelta, next.message)
    }
}

fun writeMidiToBytes(music: Midi1Music): ByteArray {
    val output = mutableListOf<Byte>()
    output.addAll("MThd".toByteArray().toList())
    output.addAll(writeInt32(6))
    output.addAll(writeInt16(music.format.toInt()))
    output.addAll(writeInt16(music.tracks.size))
    output.addAll(writeInt16(music.deltaTimeSpec))

    for (track in music.tracks) {
        val trackBytes = mutableListOf<Byte>()
        for (event in track.events) {
            trackBytes.addAll(writeVariableLengthQuantity(event.deltaTime))
            val message = event.message
            trackBytes.add(message.statusByte)

            val status = message.statusByte.toInt() and 0xFF
            val statusHigh = status and 0xF0
            when {
                status == 0xFF -> {
                    trackBytes.add(message.msb)
                    val extraData = (message as? Midi1CompoundMessage)?.extraData ?: byteArrayOf()
                    trackBytes.addAll(writeVariableLengthQuantity(extraData.size))
                    trackBytes.addAll(extraData.toList())
                }
                status == 0xF0 -> {
                    val extraData = (message as? Midi1CompoundMessage)?.extraData ?: byteArrayOf()
                    trackBytes.addAll(writeVariableLengthQuantity(extraData.size))
                    trackBytes.addAll(extraData.toList())
                }
                statusHigh == 0xC0 || statusHigh == 0xD0 -> {
                    trackBytes.add(message.msb)
                }
                else -> {
                    trackBytes.add(message.msb)
                    trackBytes.add(message.lsb)
                }
            }
        }

        output.addAll("MTrk".toByteArray().toList())
        output.addAll(writeInt32(trackBytes.size))
        output.addAll(trackBytes)
    }

    return output.toByteArray()
}

private fun writeInt32(value: Int): List<Byte> {
    return listOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

private fun writeInt16(value: Int): List<Byte> {
    return listOf(
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

private fun writeVariableLengthQuantity(value: Int): List<Byte> {
    var working = value
    var buffer = working and 0x7F
    val bytes = mutableListOf<Byte>()
    while (true) {
        working = working shr 7
        if (working <= 0) break
        buffer = (buffer shl 8) or ((working and 0x7F) or 0x80)
    }
    while (true) {
        bytes.add((buffer and 0xFF).toByte())
        if ((buffer and 0x80) != 0) {
            buffer = buffer shr 8
        } else {
            break
        }
    }
    return bytes
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            return cursor.getString(nameIndex) ?: "Unknown.mid"
        }
    }
    return uri.lastPathSegment ?: "Unknown.mid"
}
