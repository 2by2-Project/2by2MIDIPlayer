package jp.project2by2.musicplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.media3.common.util.UnstableApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.Midi1Event
import dev.atsushieno.ktmidi.Midi1SimpleMessage
import dev.atsushieno.ktmidi.Midi1CompoundMessage
import dev.atsushieno.ktmidi.MidiChannelStatus
import dev.atsushieno.ktmidi.read
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max

@UnstableApi
class EditLoopPointActivity : ComponentActivity() {
    // playbackService must be Compose State so that EditLoopPointScreen recomposes when service connects
    private var playbackService by mutableStateOf<PlaybackService?>(null)
    private var wasPlayingOnEntry = false
    private var isServiceBound = false

    // Save original settings to restore on exit
    private var originalLoopEnabled = false
    private var originalLoopMode = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = (service as PlaybackService.LocalBinder).getService()
            isServiceBound = true

            if (playbackService?.isPlaying() == true) {
                wasPlayingOnEntry = true
                playbackService?.pause()
            }

            // Save original loop settings and force enable loop
            lifecycleScope.launch {
                originalLoopEnabled = SettingsDataStore.loopEnabledFlow(this@EditLoopPointActivity).first()
                originalLoopMode = SettingsDataStore.loopModeFlow(this@EditLoopPointActivity).first()

                // Force enable loop with infinite mode for editing
                SettingsDataStore.setLoopEnabled(this@EditLoopPointActivity, true)
                SettingsDataStore.setLoopMode(this@EditLoopPointActivity, 0)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Disable system back gesture/key on this screen. Exit is only via top-left back button.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setBackGestureDisabled(true)

        val uri = readUriExtra() ?: run {
            finish()
            return
        }

        // Use 0 instead of BIND_AUTO_CREATE to avoid stopping PlaybackService when unbinding
        // PlaybackService is already running as a ForegroundService, so no need to auto-create
        bindService(
            Intent(this, PlaybackService::class.java),
            serviceConnection,
            0
        )

        setContent {
            _2by2MusicPlayerTheme {
                EditLoopPointScreen(
                    fileUri = uri,
                    playbackService = playbackService,
                    onBack = { finish() },
                    onSave = { newLoopMs, newEndMs, newFileName ->
                        lifecycleScope.launch {
                            val success = saveMidiWithNewLoopPoint(
                                this@EditLoopPointActivity,
                                uri,
                                newLoopMs,
                                newEndMs,
                                newFileName
                            )
                            if (success) finish()
                        }
                    }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // Restore original loop settings when pausing
        lifecycleScope.launch {
            SettingsDataStore.setLoopEnabled(this@EditLoopPointActivity, originalLoopEnabled)
            SettingsDataStore.setLoopMode(this@EditLoopPointActivity, originalLoopMode)
        }
    }

    override fun onDestroy() {
        setBackGestureDisabled(false)
        super.onDestroy()

        try {
            playbackService?.setTemporaryLoopPoint(null)
            playbackService?.setTemporaryEndPoint(null)
            playbackService?.pause()
        } catch (e: Exception) {
            Log.e("EditLoopPoint", "Error cleaning up playback service", e)
        }

        // Unbind service only if it was bound
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
                isServiceBound = false
            } catch (e: Exception) {
                Log.e("EditLoopPoint", "Error unbinding service", e)
            }
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

    private fun setBackGestureDisabled(disabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val decor = window.decorView
        if (disabled) {
            decor.post {
                decor.systemGestureExclusionRects = listOf(Rect(0, 0, decor.width, decor.height))
            }
        } else {
            decor.systemGestureExclusionRects = emptyList()
        }
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
    }
}

data class NoteEvent(
    val noteNumber: Int,
    val startMs: Long,
    val endMs: Long,
    val velocity: Int,
    val channel: Int
)

data class EditLoopState(
    val fileName: String,
    val midiMusic: Midi1Music,
    val noteEvents: List<NoteEvent>,
    val measurePositions: List<Long>,
    val loopPointMs: Long,
    val endPointMs: Long,
    val totalDurationMs: Long,
    val zoomLevel: Float = 1f,
    val scrollOffsetMs: Long = 0L
)

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 40f
private const val INITIAL_VISIBLE_MEASURES = 5
private const val TS_DEBUG_SAMPLE_LIMIT = 24

fun calculateInitialZoomLevel(totalDurationMs: Long, measurePositions: List<Long>): Float {
    if (totalDurationMs <= 0L) return MIN_ZOOM
    if (measurePositions.size < 2) return 10f.coerceIn(MIN_ZOOM, MAX_ZOOM)
    val targetIndex = INITIAL_VISIBLE_MEASURES.coerceAtMost(measurePositions.lastIndex)
    val startMs = measurePositions.first()
    val endMs = measurePositions[targetIndex]
    val targetWindowMs = (endMs - startMs).coerceAtLeast(1L)
    return (totalDurationMs.toFloat() / targetWindowMs.toFloat()).coerceIn(MIN_ZOOM, MAX_ZOOM)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditLoopPointScreen(
    fileUri: Uri,
    playbackService: PlaybackService?,
    onBack: () -> Unit,
    onSave: (Long, Long, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var state by remember { mutableStateOf<EditLoopState?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveFileName by remember { mutableStateOf("") }
    var isLocalPlaying by remember { mutableStateOf(false) }
    var isUserScrolling by remember { mutableStateOf(false) }
    var isMidiLoadedIntoService by remember(fileUri) { mutableStateOf(false) }

    fun updateState(transform: (EditLoopState) -> EditLoopState) {
        state = state?.let(transform)
    }

    // Parse MIDI once per file.
    LaunchedEffect(fileUri) {
        // Ensure first frame is shown before heavy parsing starts.
        withFrameNanos { }
        withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(fileUri)?.use {
                    it.readBytes().asList()
                } ?: return@withContext

                val music = Midi1Music()
                music.read(bytes)
                val fileName = resolveDisplayName(context, fileUri)

                val totalDuration = calculateTotalDurationMs(music)
                val loopPoint = findLoopPointFromMusic(music) ?: 0L
                val measures = calculateMeasurePositions(music, totalDuration)

                withContext(Dispatchers.Main) {
                    state = EditLoopState(
                        fileName = fileName,
                        midiMusic = music,
                        noteEvents = emptyList(),
                        measurePositions = measures,
                        loopPointMs = loopPoint,
                        endPointMs = totalDuration,
                        totalDurationMs = totalDuration,
                        zoomLevel = calculateInitialZoomLevel(totalDuration, measures)
                    )
                    saveFileName = fileName.substringBeforeLast('.') + "_edited.mid"
                }
            } catch (e: Exception) {
                Log.e("EditLoopPoint", "Failed to load MIDI", e)
                scope.launch {
                    snackbarHostState.showSnackbar("Failed to load MIDI: ${e.message}")
                }
            }
        }
    }

    // Deferred heavy note extraction to improve initial response.
    LaunchedEffect(state?.midiMusic) {
        val s = state ?: return@LaunchedEffect
        if (s.noteEvents.isNotEmpty()) return@LaunchedEffect
        val notes = withContext(Dispatchers.Default) {
            extractNoteEventsProgressive(s.midiMusic) { partial ->
                withContext(Dispatchers.Main) {
                    updateState { st ->
                        if (st.midiMusic === s.midiMusic) st.copy(noteEvents = partial) else st
                    }
                }
            }
        }
        updateState { st ->
            if (st.midiMusic === s.midiMusic) st.copy(noteEvents = notes) else st
        }
    }

    // Load MIDI file into PlaybackService once service becomes ready.
    LaunchedEffect(fileUri, playbackService, state?.midiMusic, isMidiLoadedIntoService) {
        if (isMidiLoadedIntoService) return@LaunchedEffect
        val service = playbackService ?: return@LaunchedEffect
        val s = state ?: return@LaunchedEffect
        val loadSuccess = service.loadMidiForEditing(fileUri.toString(), s.loopPointMs, s.endPointMs)
        if (loadSuccess) {
            delay(50)
            service.setCurrentPositionMs(0L)
            isMidiLoadedIntoService = true
            Log.i("EditLoopPoint", "MIDI loaded into PlaybackService")
        } else {
            Log.e("EditLoopPoint", "Failed to load MIDI into PlaybackService")
        }
    }

    // Sync PlaybackService with local playback state
    LaunchedEffect(isLocalPlaying) {
        if (isLocalPlaying) {
            // Set temporary loop point
            playbackService?.setTemporaryLoopPoint(state?.loopPointMs)
            playbackService?.setTemporaryEndPoint(state?.endPointMs)
            playbackService?.play()
        } else {
            isUserScrolling = false
            playbackService?.pause()
            playbackService?.setTemporaryLoopPoint(null)
            playbackService?.setTemporaryEndPoint(null)
        }
    }

    // Update temporary loop point when it changes
    LaunchedEffect(state?.loopPointMs) {
        if (isLocalPlaying) {
            playbackService?.setTemporaryLoopPoint(state?.loopPointMs)
        }
    }

    // Update temporary pseudo end point when it changes
    LaunchedEffect(state?.endPointMs) {
        if (isLocalPlaying) {
            playbackService?.setTemporaryEndPoint(state?.endPointMs)
        }
    }

    // Track playback position from PlaybackService (always update, not just when playing)
    val polledCurrentPositionMs = produceState(0L, playbackService) {
        while (isActive) {
            value = playbackService?.getCurrentPositionMs() ?: 0L
            delay(50)

            // Auto-scroll to keep current position centered (only when playing)
            if (isLocalPlaying && !isUserScrolling && playbackService?.isPlaying() == true) {
                state?.let { s ->
                    val viewportWidth = (s.totalDurationMs / s.zoomLevel).toLong()
                    val halfViewport = viewportWidth / 2
                    val targetScroll = (value - halfViewport).coerceIn(0L, max(0L, s.totalDurationMs - viewportWidth))
                    updateState { it.copy(scrollOffsetMs = targetScroll) }
                }
            }
        }
    }.value
    var dragPreviewCurrentPositionMs by remember { mutableStateOf<Long?>(null) }
    val currentPositionMs = dragPreviewCurrentPositionMs ?: polledCurrentPositionMs

    // Save dialog
    if (showSaveDialog && state != null) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save MIDI File") },
            text = {
                Column {
                    Text("Enter filename for the edited MIDI file:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveFileName,
                        onValueChange = { saveFileName = it },
                        label = { Text("Filename") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The file will be saved in Downloads folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveDialog = false
                        isSaving = true
                        scope.launch {
                            try {
                                state?.let { s ->
                                    onSave(s.loopPointMs, s.endPointMs, saveFileName)
                                    snackbarHostState.showSnackbar("Saved to Downloads/$saveFileName")
                                }
                            } catch (e: Exception) {
                                Log.e("EditLoopPoint", "Save error", e)
                                snackbarHostState.showSnackbar("Failed to save: ${e.message}")
                                isSaving = false
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_loop_point_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        isLocalPlaying = false
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    state?.let { s ->
                        IconButton(onClick = {
                            val newZoom = (s.zoomLevel * 1.5f).coerceAtMost(MAX_ZOOM)
                            updateState { it.copy(zoomLevel = newZoom) }
                        }) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In")
                        }
                        IconButton(onClick = {
                            val newZoom = (s.zoomLevel / 1.5f).coerceAtLeast(MIN_ZOOM)
                            // If zoomed out enough to fit everything, reset scroll
                            val newScroll = if (s.totalDurationMs / newZoom <= s.totalDurationMs) {
                                0L
                            } else {
                                s.scrollOffsetMs
                            }
                            updateState { it.copy(zoomLevel = newZoom, scrollOffsetMs = newScroll) }
                        }) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out")
                        }
                    }

                    IconButton(
                        onClick = { showSaveDialog = true },
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        state?.let { s ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Text(
                    text = s.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )

                PianoRollView(
                    noteEvents = s.noteEvents,
                    measurePositions = s.measurePositions,
                    currentPositionMs = currentPositionMs,
                    loopPointMs = s.loopPointMs,
                    endPointMs = s.endPointMs,
                    totalDurationMs = s.totalDurationMs,
                    zoomLevel = s.zoomLevel,
                    scrollOffsetMs = s.scrollOffsetMs,
                    onScrollChange = { newScroll ->
                        updateState { it.copy(scrollOffsetMs = newScroll) }
                    },
                    onScrollDragStart = {
                        isUserScrolling = true
                    },
                    onScrollDragEnd = {
                        isUserScrolling = false
                    },
                    onZoomChange = { newZoom, focusRatio ->
                        updateState { st ->
                            val smoothZoom = st.zoomLevel + (newZoom - st.zoomLevel) * 0.35f
                            val oldViewport = (st.totalDurationMs / st.zoomLevel).toLong().coerceAtLeast(1L)
                            val focusMs = st.scrollOffsetMs + (oldViewport * focusRatio).toLong()
                            val newViewport = (st.totalDurationMs / smoothZoom).toLong().coerceAtLeast(1L)
                            val newScroll = (focusMs - (newViewport * focusRatio).toLong()).coerceIn(
                                0L,
                                max(0L, st.totalDurationMs - newViewport)
                            )
                            st.copy(zoomLevel = smoothZoom, scrollOffsetMs = newScroll)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                SeekableTimeline(
                    currentPositionMs = currentPositionMs,
                    loopPointMs = s.loopPointMs,
                    endPointMs = s.endPointMs,
                    totalDurationMs = s.totalDurationMs,
                    zoomLevel = s.zoomLevel,
                    scrollOffsetMs = s.scrollOffsetMs,
                    onCurrentPositionDrag = { newMs ->
                        // Drag without snap for smooth movement
                        dragPreviewCurrentPositionMs = newMs
                        playbackService?.setCurrentPositionMs(newMs)
                    },
                    onCurrentPositionScrub = { newMs ->
                        val snapped = snapToEighthNote(newMs, s.midiMusic)
                        dragPreviewCurrentPositionMs = snapped
                        playbackService?.setCurrentPositionMs(snapped)
                    },
                    onLoopPointDrag = { newMs ->
                        // Drag without snap for smooth movement
                        updateState { it.copy(loopPointMs = newMs) }
                    },
                    onEndPointDrag = { newMs ->
                        // Drag without snap for smooth movement
                        updateState { it.copy(endPointMs = newMs) }
                        if (isLocalPlaying) {
                            playbackService?.setTemporaryEndPoint(newMs)
                        }
                    },
                    onCurrentPositionDragEnd = { newMs ->
                        // Snap to 8th note on drag end
                        val snapped = snapToEighthNote(newMs, s.midiMusic)
                        dragPreviewCurrentPositionMs = null
                        playbackService?.setCurrentPositionMs(snapped)
                    },
                    onCurrentPositionScrubEnd = { newMs ->
                        val snapped = snapToEighthNote(newMs, s.midiMusic)
                        dragPreviewCurrentPositionMs = null
                        playbackService?.setCurrentPositionMs(snapped)
                    },
                    onCurrentPositionDragCancel = {
                        dragPreviewCurrentPositionMs = null
                    },
                    onLoopPointDragEnd = { newMs ->
                        // Snap to 8th note on drag end
                        val snapped = snapToEighthNote(newMs, s.midiMusic)
                        updateState { it.copy(loopPointMs = snapped) }
                        playbackService?.setTemporaryLoopPoint(snapped)
                    },
                    onEndPointDragEnd = { newMs ->
                        // Snap to 8th note on drag end
                        val snapped = snapToEighthNote(newMs, s.midiMusic)
                        updateState { it.copy(endPointMs = snapped) }
                        playbackService?.setTemporaryEndPoint(snapped)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Current: ${formatMs(currentPositionMs)}", color = Color.Yellow)
                    Text("Loop Start: ${formatMs(s.loopPointMs)}", color = Color.Green)
                    Text("End Point: ${formatMs(s.endPointMs)}", color = Color.Red)
                    Text("Zoom: ${String.format("%.1f", s.zoomLevel)}x")
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(
                        onClick = {
                            isLocalPlaying = !isLocalPlaying
                        },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = if (isLocalPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Button(
                        onClick = {
                            val snapped = snapToEighthNote(currentPositionMs, s.midiMusic)
                            updateState { it.copy(loopPointMs = snapped) }
                            playbackService?.setTemporaryLoopPoint(snapped)
                        }
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Set Loop")
                    }

                    Button(
                        onClick = {
                            val snapped = snapToEighthNote(currentPositionMs, s.midiMusic)
                            updateState { it.copy(endPointMs = snapped) }
                            playbackService?.setTemporaryEndPoint(snapped)
                        }
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Set End")
                    }
                }
            }
        } ?: Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            isLocalPlaying = false
            playbackService?.setTemporaryLoopPoint(null)
            playbackService?.setTemporaryEndPoint(null)
        }
    }
}

@Composable
fun PianoRollView(
    noteEvents: List<NoteEvent>,
    measurePositions: List<Long>,
    currentPositionMs: Long,
    loopPointMs: Long,
    endPointMs: Long,
    totalDurationMs: Long,
    zoomLevel: Float,
    scrollOffsetMs: Long,
    onScrollChange: (Long) -> Unit,
    onScrollDragStart: () -> Unit = {},
    onScrollDragEnd: () -> Unit = {},
    onZoomChange: (newZoom: Float, focusXRatio: Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var interactionTick by remember { mutableLongStateOf(0L) }
    var isGestureActive by remember { mutableStateOf(false) }
    val notesFade = remember { Animatable(0f) }
    val latestScrollOffsetMs by rememberUpdatedState(scrollOffsetMs)
    val latestZoomLevel by rememberUpdatedState(zoomLevel)
    val latestTotalDurationMs by rememberUpdatedState(totalDurationMs)
    val latestOnScrollChange by rememberUpdatedState(onScrollChange)
    val latestOnScrollDragStart by rememberUpdatedState(onScrollDragStart)
    val latestOnScrollDragEnd by rememberUpdatedState(onScrollDragEnd)
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)

    LaunchedEffect(noteEvents) {
        if (noteEvents.isEmpty()) return@LaunchedEffect
        notesFade.snapTo(0f)
        notesFade.animateTo(1f, animationSpec = tween(durationMillis = 320))
    }

    LaunchedEffect(interactionTick) {
        if (interactionTick == 0L) return@LaunchedEffect
        val token = interactionTick
        delay(120)
        if (token == interactionTick && isGestureActive) {
            isGestureActive = false
            latestOnScrollDragEnd()
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(totalDurationMs) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (!isGestureActive) {
                        isGestureActive = true
                        latestOnScrollDragStart()
                    }
                    interactionTick += 1

                    if (abs(zoom - 1f) > 0.001f) {
                        val focusXRatio = if (size.width > 0f) {
                            (centroid.x / size.width).coerceIn(0f, 1f)
                        } else {
                            0.5f
                        }
                        val newZoom = (latestZoomLevel * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        latestOnZoomChange(newZoom, focusXRatio)
                    }

                    if (abs(pan.x) > 0.01f && size.width > 0f) {
                        val viewportDurationMs = (latestTotalDurationMs / latestZoomLevel).toLong()
                        val dragMs = (-pan.x / size.width * viewportDurationMs).toLong()
                        val newScroll = (latestScrollOffsetMs + dragMs).coerceIn(
                            0L,
                            max(0L, latestTotalDurationMs - viewportDurationMs)
                        )
                        latestOnScrollChange(newScroll)
                    }
                }
            }
    ) {
        val width = size.width
        val height = size.height

        drawRect(Color(0xFF1E1E1E))

        val viewportDurationMs = (totalDurationMs / zoomLevel).toLong()
        val visibleStartMs = scrollOffsetMs
        val visibleEndMs = scrollOffsetMs + viewportDurationMs

        // Draw measure lines (小節線)
        measurePositions.filter { it in visibleStartMs..visibleEndMs }.forEach { measureMs ->
            val x = ((measureMs - visibleStartMs).toFloat() / viewportDurationMs.toFloat()) * width
            drawLine(
                color = Color.Gray.copy(alpha = 0.5f),
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 2f
            )
        }

        noteEvents.filter { note ->
            note.startMs < visibleEndMs && note.endMs > visibleStartMs
        }.forEach { note ->
            val x = ((note.startMs - visibleStartMs).toFloat() / viewportDurationMs.toFloat()) * width
            val noteWidth = ((note.endMs - note.startMs).toFloat() / viewportDurationMs.toFloat()) * width
            val y = ((127 - note.noteNumber).toFloat() / 127f) * height
            val noteHeight = height / 128f * 2
            //　val leftFade = (x / (width * 0.25f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val noteAlpha = 1.0f // (0.15f + 0.55f * leftFade) * notesFade.value

            drawRect(
                color = Color.Blue.copy(alpha = noteAlpha.coerceIn(0f, 0.7f)),
                topLeft = Offset(x, y),
                size = Size(noteWidth.coerceAtLeast(2f), noteHeight)
            )
        }

        if (endPointMs in visibleStartMs..visibleEndMs) {
            val endX = ((endPointMs - visibleStartMs).toFloat() / viewportDurationMs.toFloat()) * width
            drawLine(
                color = Color.Red,
                start = Offset(endX, 0f),
                end = Offset(endX, height),
                strokeWidth = 4f
            )
        }

        if (loopPointMs in visibleStartMs..visibleEndMs) {
            val loopX = ((loopPointMs - visibleStartMs).toFloat() / viewportDurationMs.toFloat()) * width
            drawLine(
                color = Color.Green,
                start = Offset(loopX, 0f),
                end = Offset(loopX, height),
                strokeWidth = 4f
            )
        }

        if (currentPositionMs in visibleStartMs..visibleEndMs) {
            val currentX = ((currentPositionMs - visibleStartMs).toFloat() / viewportDurationMs.toFloat()) * width
            drawLine(
                color = Color.Yellow,
                start = Offset(currentX, 0f),
                end = Offset(currentX, height),
                strokeWidth = 3f
            )
        }
    }
}

@Composable
fun SeekableTimeline(
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
        modifier = modifier.background(Color.Gray.copy(alpha = 0.2f))
    ) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val viewportDurationMs = (totalDurationMs / zoomLevel).toLong()
        val visibleStartMs = scrollOffsetMs
        var scrubX by remember { mutableFloatStateOf(0f) }

        fun msToX(ms: Long): Float {
            return ((ms - visibleStartMs).toFloat() / viewportDurationMs.toFloat()) * maxWidthPx
        }

        fun xToMs(x: Float): Long {
            return visibleStartMs + (x / maxWidthPx * viewportDurationMs.toFloat()).toLong()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(totalDurationMs, zoomLevel, scrollOffsetMs, maxWidthPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            scrubX = offset.x.coerceIn(0f, maxWidthPx)
                            val newMs = xToMs(scrubX).coerceIn(0L, totalDurationMs)
                            onCurrentPositionScrub(newMs)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            scrubX = (scrubX + dragAmount.x).coerceIn(0f, maxWidthPx)
                            val newMs = xToMs(scrubX).coerceIn(0L, totalDurationMs)
                            onCurrentPositionScrub(newMs)
                        },
                        onDragEnd = {
                            val newMs = xToMs(scrubX).coerceIn(0L, totalDurationMs)
                            onCurrentPositionScrubEnd(newMs)
                        },
                        onDragCancel = {
                            val newMs = xToMs(scrubX).coerceIn(0L, totalDurationMs)
                            onCurrentPositionScrubEnd(newMs)
                        }
                    )
                }
        )

        // Background
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Gray.copy(alpha = 0.2f))

            // Draw vertical lines for end, loop, and current positions
            if (endPointMs >= visibleStartMs && endPointMs <= visibleStartMs + viewportDurationMs) {
                val x = msToX(endPointMs)
                drawLine(
                    color = Color.Red.copy(alpha = 0.3f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f
                )
            }

            if (loopPointMs >= visibleStartMs && loopPointMs <= visibleStartMs + viewportDurationMs) {
                val x = msToX(loopPointMs)
                drawLine(
                    color = Color.Green.copy(alpha = 0.3f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f
                )
            }

            if (currentPositionMs >= visibleStartMs && currentPositionMs <= visibleStartMs + viewportDurationMs) {
                val x = msToX(currentPositionMs)
                drawLine(
                    color = Color.Yellow.copy(alpha = 0.3f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f
                )
            }
        }

        // Draggable handles
        if (endPointMs >= visibleStartMs && endPointMs <= visibleStartMs + viewportDurationMs) {
            key("timeline-end-handle") {
                DraggableTimelineHandle(
                    id = "end",
                    position = msToX(endPointMs),
                    color = Color.Red,
                    maxWidthPx = maxWidthPx,
                    onDrag = { newX ->
                        val newMs = xToMs(newX).coerceIn(0L, totalDurationMs)
                        onEndPointDrag(newMs)
                    },
                    onDragEnd = { newX ->
                        val newMs = xToMs(newX).coerceIn(0L, totalDurationMs)
                        onEndPointDragEnd(newMs)
                    },
                    onDragCancel = null
                )
            }
        }

        if (loopPointMs >= visibleStartMs && loopPointMs <= visibleStartMs + viewportDurationMs) {
            key("timeline-loop-handle") {
                DraggableTimelineHandle(
                    id = "loop",
                    position = msToX(loopPointMs),
                    color = Color.Green,
                    maxWidthPx = maxWidthPx,
                    onDrag = { newX ->
                        val newMs = xToMs(newX).coerceIn(0L, totalDurationMs)
                        onLoopPointDrag(newMs)
                    },
                    onDragEnd = { newX ->
                        val newMs = xToMs(newX).coerceIn(0L, totalDurationMs)
                        onLoopPointDragEnd(newMs)
                    },
                    onDragCancel = null
                )
            }
        }

        if (currentPositionMs >= visibleStartMs && currentPositionMs <= visibleStartMs + viewportDurationMs) {
            key("timeline-current-handle") {
                DraggableTimelineHandle(
                    id = "current",
                    position = msToX(currentPositionMs),
                    color = Color.Yellow,
                    maxWidthPx = maxWidthPx,
                    onDrag = { newX ->
                        val newMs = xToMs(newX).coerceIn(0L, totalDurationMs)
                        onCurrentPositionDrag(newMs)
                    },
                    onDragEnd = { newX ->
                        val newMs = xToMs(newX).coerceIn(0L, totalDurationMs)
                        onCurrentPositionDragEnd(newMs)
                    },
                    onDragCancel = onCurrentPositionDragCancel
                )
            }
        }
    }
}

@Composable
fun DraggableTimelineHandle(
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
                    with(density) { currentX.toDp().roundToPx() - 12.dp.roundToPx() },
                    0
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
                .clip(CircleShape)
                .background(color)
        )
    }
}

fun extractNoteEvents(music: Midi1Music): List<NoteEvent> {
    val notes = mutableListOf<NoteEvent>()
    val tickMsCache = HashMap<Int, Long>(4096)

    fun tickToMs(tick: Int): Long {
        return tickMsCache.getOrPut(tick) { music.getTimePositionInMillisecondsForTick(tick).toLong() }
    }

    for (track in music.tracks) {
        var tick = 0
        val activeNotes = mutableMapOf<Pair<Int, Int>, Pair<Int, Long>>()

        for (event in track.events) {
            tick += event.deltaTime
            val msg = event.message
            val status = msg.statusByte.toInt() and 0xF0
            val channel = msg.statusByte.toInt() and 0x0F

            when (status) {
                MidiChannelStatus.NOTE_ON -> {
                    val note = msg.msb.toInt()
                    val velocity = msg.lsb.toInt()
                    if (velocity > 0) {
                        val startMs = tickToMs(tick)
                        activeNotes[note to channel] = velocity to startMs
                    } else {
                        activeNotes[note to channel]?.let { (vel, startMs) ->
                            val endMs = tickToMs(tick)
                            notes.add(NoteEvent(note, startMs, endMs, vel, channel))
                            activeNotes.remove(note to channel)
                        }
                    }
                }
                MidiChannelStatus.NOTE_OFF -> {
                    val note = msg.msb.toInt()
                    activeNotes[note to channel]?.let { (vel, startMs) ->
                        val endMs = tickToMs(tick)
                        notes.add(NoteEvent(note, startMs, endMs, vel, channel))
                        activeNotes.remove(note to channel)
                    }
                }
            }
        }
    }

    return notes
}

suspend fun extractNoteEventsProgressive(
    music: Midi1Music,
    chunkSize: Int = 400,
    onChunk: suspend (List<NoteEvent>) -> Unit
): List<NoteEvent> {
    data class TrackCursor(
        val trackIndex: Int,
        var eventIndex: Int,
        var absoluteTick: Int
    )

    val notes = mutableListOf<NoteEvent>()
    val tickMsCache = HashMap<Int, Long>(4096)
    var lastPublishedSize = 0
    val activeByTrack = Array(music.tracks.size) { mutableMapOf<Pair<Int, Int>, Pair<Int, Long>>() }
    val queue = PriorityQueue<TrackCursor> { a, b ->
        val byTick = a.absoluteTick.compareTo(b.absoluteTick)
        if (byTick != 0) byTick else a.trackIndex.compareTo(b.trackIndex)
    }

    fun tickToMs(tick: Int): Long {
        return tickMsCache.getOrPut(tick) { music.getTimePositionInMillisecondsForTick(tick).toLong() }
    }

    suspend fun publishIfNeeded(force: Boolean = false) {
        if (force || notes.size - lastPublishedSize >= chunkSize) {
            lastPublishedSize = notes.size
            onChunk(notes.toList())
        }
    }

    for ((trackIndex, track) in music.tracks.withIndex()) {
        if (track.events.isNotEmpty()) {
            queue.add(TrackCursor(trackIndex = trackIndex, eventIndex = 0, absoluteTick = track.events[0].deltaTime))
        }
    }

    while (queue.isNotEmpty()) {
        val cursor = queue.poll()
        val track = music.tracks[cursor.trackIndex]
        val event = track.events[cursor.eventIndex]
        val tick = cursor.absoluteTick
        val activeNotes = activeByTrack[cursor.trackIndex]

        val msg = event.message
        val status = msg.statusByte.toInt() and 0xF0
        val channel = msg.statusByte.toInt() and 0x0F

        when (status) {
            MidiChannelStatus.NOTE_ON -> {
                val note = msg.msb.toInt()
                val velocity = msg.lsb.toInt()
                if (velocity > 0) {
                    val startMs = tickToMs(tick)
                    activeNotes[note to channel] = velocity to startMs
                } else {
                    activeNotes[note to channel]?.let { (vel, startMs) ->
                        val endMs = tickToMs(tick)
                        notes.add(NoteEvent(note, startMs, endMs, vel, channel))
                        activeNotes.remove(note to channel)
                        publishIfNeeded()
                    }
                }
            }
            MidiChannelStatus.NOTE_OFF -> {
                val note = msg.msb.toInt()
                activeNotes[note to channel]?.let { (vel, startMs) ->
                    val endMs = tickToMs(tick)
                    notes.add(NoteEvent(note, startMs, endMs, vel, channel))
                    activeNotes.remove(note to channel)
                    publishIfNeeded()
                }
            }
        }

        val nextIndex = cursor.eventIndex + 1
        if (nextIndex < track.events.size) {
            cursor.eventIndex = nextIndex
            cursor.absoluteTick += track.events[nextIndex].deltaTime
            queue.add(cursor)
        }
    }

    publishIfNeeded(force = true)
    return notes
}

data class TempoChange(val tick: Int, val microsecondsPerQuarterNote: Int)
data class TimeSignature(val tick: Int, val numerator: Int, val denominator: Int)

fun extractTempoChanges(music: Midi1Music): List<TempoChange> {
    val tempos = mutableListOf<TempoChange>()

    for (track in music.tracks) {
        var tick = 0
        for (event in track.events) {
            tick += event.deltaTime
            val msg = event.message
            // Use unsigned comparison: 0xFF.toByte() == -1 in Kotlin
            if ((msg.statusByte.toInt() and 0xFF) == 0xFF && (msg.msb.toInt() and 0xFF) == 0x51) {
                val data = (msg as? Midi1CompoundMessage)?.extraData ?: continue
                if (data.size >= 3) {
                    val tempo = ((data[0].toInt() and 0xFF) shl 16) or
                               ((data[1].toInt() and 0xFF) shl 8) or
                               (data[2].toInt() and 0xFF)
                    tempos.add(TempoChange(tick, tempo))
                }
            }
        }
    }

    return tempos.ifEmpty { listOf(TempoChange(0, 500000)) } // Default 120 BPM
}

fun extractTimeSignatures(music: Midi1Music): List<TimeSignature> {
    data class RawTimeSignature(
        val tick: Int,
        val numerator: Int,
        val denominator: Int,
        val trackIndex: Int
    )

    val raw = mutableListOf<RawTimeSignature>()
    for ((trackIndex, track) in music.tracks.withIndex()) {
        var tick = 0
        for (event in track.events) {
            tick += event.deltaTime
            val msg = event.message
            // Use unsigned comparison: 0xFF.toByte() == -1 in Kotlin
            if ((msg.statusByte.toInt() and 0xFF) == 0xFF && (msg.msb.toInt() and 0xFF) == 0x58) {
                val data = (msg as? Midi1CompoundMessage)?.extraData ?: continue
                if (data.size >= 2) {
                    val numerator = data[0].toInt() and 0xFF
                    val denominatorPow = data[1].toInt() and 0xFF
                    if (numerator <= 0 || denominatorPow !in 0..7) continue
                    val denominator = 1 shl denominatorPow // 2^denominator
                    raw.add(
                        RawTimeSignature(
                            tick = tick.coerceAtLeast(0),
                            numerator = numerator,
                            denominator = denominator,
                            trackIndex = trackIndex
                        )
                    )
                }
            }
        }
    }

    if (raw.isEmpty()) return listOf(TimeSignature(0, 4, 4))

    val maxTick = music.tracks.maxOfOrNull { track ->
        track.events.sumOf { it.deltaTime }
    } ?: 0
    if (maxTick <= 0) return listOf(TimeSignature(0, 4, 4))

    val normalized = raw
        .groupBy { it.tick }
        .toSortedMap()
        .map { (tick, entriesAtTick) ->
            val chosen = entriesAtTick
                .sortedWith(
                    compareByDescending<RawTimeSignature> { it.numerator == 4 && it.denominator == 4 }
                        .thenBy { it.trackIndex }
                )
                .first()
            TimeSignature(tick, chosen.numerator, chosen.denominator)
        }
        .toMutableList()

    fun ticksPerMeasure(sig: TimeSignature): Int {
        return ((sig.numerator * 4 * music.deltaTimeSpec) / sig.denominator).coerceAtLeast(1)
    }

    // Choose baseline by "adopted measure count":
    // for each segment between time-signature events, accumulate how many measures that signature occupies.
    val measureCoverage = mutableMapOf<Pair<Int, Int>, Double>()
    val tick0Sig = normalized.firstOrNull { it.tick == 0 } ?: TimeSignature(0, 4, 4)
    var currentSig = tick0Sig
    var currentTick = 0
    for (sig in normalized) {
        if (sig.tick <= 0) continue
        val segmentEnd = sig.tick.coerceIn(0, maxTick)
        if (segmentEnd > currentTick) {
            val ticks = (segmentEnd - currentTick).toDouble()
            val measures = ticks / ticksPerMeasure(currentSig).toDouble()
            val key = currentSig.numerator to currentSig.denominator
            measureCoverage[key] = (measureCoverage[key] ?: 0.0) + measures
            currentTick = segmentEnd
        }
        currentSig = sig
    }
    if (maxTick > currentTick) {
        val ticks = (maxTick - currentTick).toDouble()
        val measures = ticks / ticksPerMeasure(currentSig).toDouble()
        val key = currentSig.numerator to currentSig.denominator
        measureCoverage[key] = (measureCoverage[key] ?: 0.0) + measures
    }

    val basePair = measureCoverage.entries
        .maxWithOrNull(
            compareBy<Map.Entry<Pair<Int, Int>, Double>> { it.value }
                .thenBy { if (it.key == (4 to 4)) 1 else 0 }
        )
        ?.key ?: (4 to 4)

    // Always anchor display baseline at tick 0 with the selected base signature.
    // This avoids starting from a short 2/4 or 1/8 marker that makes the whole roll look "double speed".
    normalized.removeAll { it.tick == 0 }
    normalized.add(0, TimeSignature(0, basePair.first, basePair.second))

    // Keep timeline compact: drop no-op consecutive duplicates after normalization.
    val deduped = mutableListOf<TimeSignature>()
    for (sig in normalized.sortedBy { it.tick }) {
        val prev = deduped.lastOrNull()
        if (prev == null || prev.numerator != sig.numerator || prev.denominator != sig.denominator) {
            deduped.add(sig)
        }
    }

    if (BuildConfig.DEBUG) {
        val rawSample = raw
            .sortedBy { it.tick }
            .take(TS_DEBUG_SAMPLE_LIMIT)
            .joinToString(", ") { "t${it.tick}:${it.numerator}/${it.denominator}(trk${it.trackIndex})" }
        val coverageSample = measureCoverage.entries
            .sortedByDescending { it.value }
            .take(TS_DEBUG_SAMPLE_LIMIT)
            .joinToString(", ") { "${it.key.first}/${it.key.second}=${"%.2f".format(it.value)}bars" }
        val normalizedSample = deduped
            .take(TS_DEBUG_SAMPLE_LIMIT)
            .joinToString(", ") { "t${it.tick}:${it.numerator}/${it.denominator}" }
        Log.i(
            "EditLoopPointTS",
            "TS analyze: tpq=${music.deltaTimeSpec} maxTick=$maxTick rawCount=${raw.size} normalizedCount=${deduped.size} base=${basePair.first}/${basePair.second}"
        )
        Log.d("EditLoopPointTS", "raw(sample): $rawSample")
        Log.d("EditLoopPointTS", "coverage(sample): $coverageSample")
        Log.d("EditLoopPointTS", "normalized(sample): $normalizedSample")
    }

    return deduped
}

fun calculateMeasurePositions(music: Midi1Music, totalDurationMs: Long): List<Long> {
    val signatures = extractTimeSignatures(music)
    val ticksPerQuarterNote = music.deltaTimeSpec
    val measures = mutableListOf<Long>()
    val tickMsCache = HashMap<Int, Long>(2048)

    var currentTick = 0
    val maxTick = music.tracks.maxOfOrNull { track ->
        track.events.sumOf { it.deltaTime }
    } ?: 0

    while (currentTick < maxTick) {
        val ms = tickMsCache.getOrPut(currentTick) {
            music.getTimePositionInMillisecondsForTick(currentTick).toLong()
        }
        measures.add(ms)

        // Get current time signature
        val currentSig = signatures.lastOrNull { it.tick <= currentTick } ?: signatures.first()

        // Calculate ticks per measure: (numerator / denominator) * 4 * ticksPerQuarterNote
        val ticksPerMeasure = (currentSig.numerator * 4 * ticksPerQuarterNote) / currentSig.denominator
        currentTick += ticksPerMeasure
    }

    if (BuildConfig.DEBUG && measures.isNotEmpty()) {
        val spansSample = measures
            .zipWithNext()
            .take(TS_DEBUG_SAMPLE_LIMIT)
            .joinToString(", ") { (a, b) -> "${b - a}ms" }
        val pointsSample = measures
            .take(TS_DEBUG_SAMPLE_LIMIT)
            .joinToString(", ") { "${it}ms" }
        Log.i(
            "EditLoopPointTS",
            "measurePositions: count=${measures.size} totalDurationMs=$totalDurationMs first=${measures.first()} last=${measures.lastOrNull() ?: 0L}"
        )
        Log.d("EditLoopPointTS", "measurePoints(sample): $pointsSample")
        Log.d("EditLoopPointTS", "measureSpans(sample): $spansSample")
    }

    return measures
}

fun snapToEighthNote(targetMs: Long, music: Midi1Music): Long {
    val targetTick = approximateTickFromMs(targetMs, music)
    val ticksPerQuarterNote = music.deltaTimeSpec
    val ticksPerEighthNote = ticksPerQuarterNote / 2

    val snappedTick = ((targetTick + ticksPerEighthNote / 2) / ticksPerEighthNote) * ticksPerEighthNote
    return music.getTimePositionInMillisecondsForTick(snappedTick).toLong()
}

fun approximateTickFromMs(targetMs: Long, music: Midi1Music): Int {
    val maxTick = music.tracks.maxOfOrNull { track ->
        track.events.sumOf { it.deltaTime }
    } ?: 0

    var low = 0
    var high = maxTick

    while (low < high) {
        val mid = (low + high) / 2
        val midMs = music.getTimePositionInMillisecondsForTick(mid).toLong()
        if (midMs < targetMs) {
            low = mid + 1
        } else {
            high = mid
        }
    }

    return low
}

fun findLoopPointFromMusic(music: Midi1Music): Long? {
    val tickMsCache = HashMap<Int, Long>(512)

    for (track in music.tracks) {
        var tick = 0
        for (e in track.events) {
            tick += e.deltaTime
            val m = e.message
            val isCC = ((m.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC)
            if (isCC && m.msb.toInt() == 111) {
                return tickMsCache.getOrPut(tick) {
                    music.getTimePositionInMillisecondsForTick(tick).toLong()
                }
            }
        }
    }
    return null
}

fun calculateTotalDurationMs(music: Midi1Music): Long {
    val maxTick = music.tracks.maxOfOrNull { track ->
        track.events.sumOf { it.deltaTime }
    } ?: 0
    return music.getTimePositionInMillisecondsForTick(maxTick).toLong()
}

suspend fun saveMidiWithNewLoopPoint(
    context: Context,
    uri: Uri,
    newLoopMs: Long,
    newEndMs: Long,
    newFileName: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        Log.i("EditLoopPoint", "Starting save: $newFileName")

        val bytes = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().asList()
        } ?: run {
            Log.e("EditLoopPoint", "Failed to read input stream")
            return@withContext false
        }

        val music = Midi1Music()
        music.read(bytes)

        val newLoopTick = approximateTickFromMs(newLoopMs, music)
        val newEndTick = approximateTickFromMs(newEndMs, music)

        for (track in music.tracks) {
            track.events.removeAll { event ->
                val msg = event.message
                val isCC = ((msg.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC)
                isCC && msg.msb.toInt() == 111
            }
        }

        Log.i("EditLoopPoint", "Processing ${music.tracks.size} tracks, newEndTick=$newEndTick")

        for ((trackIndex, track) in music.tracks.withIndex()) {
            var accumulatedTick = 0
            val eventsToKeep = mutableListOf<Midi1Event>()
            val metaEventsAfterEnd = mutableListOf<Midi1Event>()
            var metaEventCount = 0

            for (event in track.events) {
                val msg = event.message
                // Use unsigned comparison: 0xFF.toByte() == -1 in Kotlin/JVM
                val statusU = msg.statusByte.toInt() and 0xFF
                val msbU = msg.msb.toInt() and 0xFF
                val isMetaEvent = (statusU == 0xFF) && msbU != 0x2F // Not End of Track
                val isSysEx = (statusU == 0xF0)

                // Log meta events for debugging
                if (statusU == 0xFF) {
                    when (msbU) {
                        0x51 -> {
                            metaEventCount++
                            Log.d("EditLoopPoint", "Track $trackIndex: Tempo event at tick $accumulatedTick")
                        }
                        0x58 -> {
                            metaEventCount++
                            Log.d("EditLoopPoint", "Track $trackIndex: Time Signature at tick $accumulatedTick")
                        }
                    }
                }

                accumulatedTick += event.deltaTime

                if (accumulatedTick <= newEndTick) {
                    eventsToKeep.add(event)
                } else {
                    // Keep meta events and SysEx even after end point (except End of Track)
                    if (isMetaEvent || isSysEx) {
                        metaEventsAfterEnd.add(event)
                    } else {
                        // Stop processing note events after end point
                        break
                    }
                }
            }

            // Add meta events that occurred after the end point (with deltaTime = 0)
            metaEventsAfterEnd.forEach { metaEvent ->
                eventsToKeep.add(Midi1Event(0, metaEvent.message))
            }

            // Add End of Track event
            if (eventsToKeep.isEmpty() || !isEndOfTrack(eventsToKeep.last())) {
                val lastTick = eventsToKeep.sumOf { it.deltaTime }
                val endDelta = (newEndTick - lastTick).coerceAtLeast(0)
                eventsToKeep.add(createEndOfTrackEvent(endDelta))
            }

            Log.i("EditLoopPoint", "Track $trackIndex: Kept ${eventsToKeep.size} events ($metaEventCount meta events)")

            track.events.clear()
            track.events.addAll(eventsToKeep)
        }

        if (music.tracks.isNotEmpty()) {
            val firstTrack = music.tracks[0]
            insertCCEvent(firstTrack, newLoopTick, 111, 0)
        }

        val outputBytes = writeMidiToBytes(music)

        // Save to public Downloads folder using MediaStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, newFileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/midi")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/2by2midi")
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(outputBytes)
                }
                Log.i("EditLoopPoint", "File saved successfully to MediaStore: $uri (${outputBytes.size} bytes)")
            } else {
                Log.e("EditLoopPoint", "Failed to create MediaStore entry")
                return@withContext false
            }
        } else {
            // For older Android versions, save to public Downloads folder
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val subDir = File(downloadsDir, "2by2midi")
            if (!subDir.exists()) {
                subDir.mkdirs()
            }

            val outputFile = File(subDir, newFileName)
            outputFile.writeBytes(outputBytes)
            Log.i("EditLoopPoint", "File saved successfully: ${outputFile.absolutePath} (${outputBytes.size} bytes)")
        }

        return@withContext true
    } catch (e: Exception) {
        Log.e("EditLoopPoint", "Failed to save MIDI", e)
        return@withContext false
    }
}

fun isEndOfTrack(event: Midi1Event): Boolean {
    val msg = event.message
    // Use unsigned comparison
    return (msg.statusByte.toInt() and 0xFF) == 0xFF && (msg.msb.toInt() and 0xFF) == 0x2F
}

fun createEndOfTrackEvent(deltaTime: Int): Midi1Event {
    val message = Midi1CompoundMessage(0xFF, 0x2F, 0, byteArrayOf())
    return Midi1Event(deltaTime, message)
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
    val statusByte = (MidiChannelStatus.CC or 0)
    val message = Midi1SimpleMessage(statusByte, ccNumber, value)
    val ccEvent = Midi1Event(deltaTime, message)

    track.events.add(insertIndex, ccEvent)

    if (insertIndex + 1 < track.events.size) {
        val nextEvent = track.events[insertIndex + 1]
        val newDelta = (nextEvent.deltaTime - deltaTime).coerceAtLeast(0)
        track.events[insertIndex + 1] = Midi1Event(newDelta, nextEvent.message)
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

            val msg = event.message
            trackBytes.add(msg.statusByte)

            // Use unsigned comparison (0xFF.toByte() == -1 in Kotlin/JVM)
            val statusU = msg.statusByte.toInt() and 0xFF
            val statusHigh = statusU and 0xF0
            when {
                statusU == 0xFF -> {
                    // Meta event: statusByte(FF) + type + length + data
                    trackBytes.add(msg.msb)
                    if (msg is Midi1CompoundMessage) {
                        val extraData = msg.extraData ?: byteArrayOf()
                        trackBytes.addAll(writeVariableLengthQuantity(extraData.size))
                        trackBytes.addAll(extraData.toList())
                    } else {
                        trackBytes.addAll(writeVariableLengthQuantity(0))
                    }
                }
                statusU == 0xF0 -> {
                    // SysEx event
                    if (msg is Midi1CompoundMessage) {
                        val extraData = msg.extraData ?: byteArrayOf()
                        trackBytes.addAll(writeVariableLengthQuantity(extraData.size))
                        trackBytes.addAll(extraData.toList())
                    } else {
                        trackBytes.addAll(writeVariableLengthQuantity(0))
                    }
                }
                statusHigh == 0xC0 || statusHigh == 0xD0 -> {
                    // Program change / Channel pressure: 1 data byte
                    trackBytes.add(msg.msb)
                }
                else -> {
                    // All other events: 2 data bytes
                    trackBytes.add(msg.msb)
                    trackBytes.add(msg.lsb)
                }
            }
        }

        output.addAll("MTrk".toByteArray().toList())
        output.addAll(writeInt32(trackBytes.size))
        output.addAll(trackBytes)
    }

    return output.toByteArray()
}

fun writeInt32(value: Int): List<Byte> {
    return listOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

fun writeInt16(value: Int): List<Byte> {
    return listOf(
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )
}

fun writeVariableLengthQuantity(value: Int): List<Byte> {
    val bytes = mutableListOf<Byte>()
    var v = value

    bytes.add((v and 0x7F).toByte())
    v = v shr 7

    while (v > 0) {
        bytes.add(0, ((v and 0x7F) or 0x80).toByte())
        v = v shr 7
    }

    return bytes
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(idx)
                if (!name.isNullOrBlank()) return name
            }
        }
    return uri.lastPathSegment?.substringAfterLast('/') ?: context.getString(R.string.unknown)
}

private fun formatMs(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%d:%02d", minutes, remainingSeconds)
}
