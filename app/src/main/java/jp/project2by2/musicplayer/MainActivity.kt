package jp.project2by2.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.read
import jp.project2by2.musicplayer.ui.theme._2by2MusicPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var externalOpenUri by mutableStateOf<Uri?>(null)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleViewIntent(intent)
        enableEdgeToEdge()
        setContent {
            _2by2MusicPlayerTheme {
                MusicPlayerMainScreen(
                    externalOpenUri = externalOpenUri,
                    onExternalOpenConsumed = { externalOpenUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        val type = (intent.type ?: contentResolver.getType(uri) ?: "").lowercase()
        val path = uri.toString().lowercase()
        val isMidi = type in setOf("audio/midi", "audio/x-midi", "application/midi", "application/x-midi")
                || path.endsWith(".mid") || path.endsWith(".midi")
        if (!isMidi) return

        externalOpenUri = uri
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerMainScreen(
    externalOpenUri: Uri? = null,
    onExternalOpenConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedMidiFileUri by remember { mutableStateOf<Uri?>(null) }

    var playbackService by remember { mutableStateOf<PlaybackService?>(null) }
    var isBound by remember { mutableStateOf(false) }

    var showSoundFontDialog by remember { mutableStateOf(false) }

    // Playing state (for bottom bar)
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFolderKey by remember { mutableStateOf<String?>(null) }
    var selectedFolderName by remember { mutableStateOf<String?>(null) }
    var selectedFolderCoverUri by remember { mutableStateOf<Uri?>(null) }
    var rootTab by remember { mutableStateOf(RootTab.Browse) }
    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }
    var selectedPlaylistName by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistRefreshToken by remember { mutableLongStateOf(0L) }
    var pendingPlaylistCandidate by remember { mutableStateOf<MidiFileItem?>(null) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var pianoRollData by remember { mutableStateOf<PianoRollData?>(null) }
    var miniLiftProgress by remember { mutableFloatStateOf(0f) }
    var miniLiftDragPx by remember { mutableFloatStateOf(0f) }
    var skipNowPlayingEnterAnimation by remember { mutableStateOf(false) }

    val midiFiles = remember { mutableStateListOf<MidiFileItem>() }
    val folderItems = remember { mutableStateListOf<FolderItem>() }

    var isDemoLoading by remember { mutableStateOf(false) }
    var demoFilesLoaded by remember { mutableStateOf(false) }
    val playlistRepository = remember(context) { PlaylistStore.repository(context) }

    // Media3
    val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
    controllerFuture.addListener({
        // MediaController is available here with controllerFuture.get()
    }, MoreExecutors.directExecutor())

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val imagePermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            audioPermission,
            imagePermission,
        )
    } else {
        arrayOf(audioPermission)
    }
    var hasAudioPermission by remember {
        mutableStateOf(hasPermission(context, audioPermission))
    }
    var hasImagePermission by remember {
        mutableStateOf(hasPermission(context, imagePermission))
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasAudioPermission = results[audioPermission] == true
        hasImagePermission = results[imagePermission] == true
    }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val binder = service as? PlaybackService.LocalBinder
                playbackService = binder?.getService()
                isBound = playbackService != null
            }

            override fun onServiceDisconnected(name: ComponentName) {
                playbackService = null
                isBound = false
            }
        }
    }

    DisposableEffect(Unit) {
        val intent = Intent(context, PlaybackService::class.java)
        context.startService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose {
            if (isBound) {
                context.unbindService(serviceConnection)
            }
        }
    }

    LaunchedEffect(playbackService) {
        val service = playbackService ?: return@LaunchedEffect

        while (playbackService === service) {
            selectedMidiFileUri = service.getCurrentUriString()?.let { Uri.parse(it) }
            delay(250)
        }
    }

    LaunchedEffect(hasAudioPermission, hasImagePermission) {
        if (!hasAudioPermission) return@LaunchedEffect

        val (files, folders) = withContext(Dispatchers.IO) {
            val f = queryMidiFiles(context)
            val d = buildFolderItems(context, f, hasImagePermission)
            f to d
        }

        midiFiles.clear()
        midiFiles.addAll(files)

        folderItems.clear()
        folderItems.addAll(folders)

        // Restore demo files from persistent storage if previously loaded
        val loaded = SettingsDataStore.demoFilesLoadedFlow(context).first()
        demoFilesLoaded = loaded
        if (loaded) {
            withContext(Dispatchers.IO) {
                try {
                    val demoFiles = queryDemoMidiFiles(context)
                    midiFiles.removeAll { it.folderKey == "assets_demo" }
                    midiFiles.addAll(demoFiles)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val needsAnyPermission = if (Build.VERSION.SDK_INT >= 33) {
            !hasAudioPermission || !hasImagePermission
        } else {
            !hasAudioPermission
        }
        if (needsAnyPermission) {
            storagePermissionLauncher.launch(permissionsToRequest)
        }

        // Check if SoundFont is set
        val cacheSoundFontFile = File(context.cacheDir, "soundfont.sf2")
        if (!cacheSoundFontFile.exists()) {
            showSoundFontDialog = true
        }
    }

    // Back handler
    BackHandler(enabled = selectedPlaylistId != null || selectedFolderKey != null || isSearchActive) {
        when {
            isSearchActive -> {
                isSearchActive = false
                searchQuery = ""
            }
            selectedPlaylistId != null -> {
                selectedPlaylistId = null
                selectedPlaylistName = null
            }
            selectedFolderKey != null -> {
                selectedFolderKey = null
                selectedFolderName = null
            }
        }
    }
    BackHandler(enabled = showNowPlaying) {
        showNowPlaying = false
    }

    fun handleMidiTap(
        uri: Uri,
        sourceItems: List<MidiFileItem>? = null,
        sourceTitle: String? = null,
        sourceCover: Uri? = null,
        configureTransientQueue: Boolean = true
    ) {
        val cacheSoundFontFile = File(context.cacheDir, "soundfont.sf2")
        if (!cacheSoundFontFile.exists()) {
            Toast.makeText(context, context.getString(R.string.error_soundfont_not_set), Toast.LENGTH_LONG).show()
            return
        }
        selectedMidiFileUri = uri

        // Set artist and cover uri
        val service = playbackService
        if (service == null) {
            Toast.makeText(context, context.getString(R.string.error_playback_service_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (configureTransientQueue) {
            val queueItems = sourceItems?.map { it.uri.toString() } ?: listOf(uri.toString())
            service.setTransientQueue(
                items = queueItems,
                title = sourceTitle ?: selectedFolderName,
                startUri = uri.toString()
            )
        }
        service.currentArtist = sourceTitle ?: selectedFolderName
        service.currentArtworkUri = sourceCover ?: selectedFolderCoverUri

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                service.loadMidi(uri.toString())
            }
            if (!ok) {
                Toast.makeText(context, context.getString(R.string.error_failed_to_load_midi), Toast.LENGTH_SHORT).show()
                return@launch
            }
            controllerFuture.addListener(
                {
                    runCatching { controllerFuture.get().play() }
                        .onSuccess {
                            scope.launch {
                                skipNowPlayingEnterAnimation = false
                                showNowPlaying = true
                                miniLiftProgress = 0f
                                miniLiftDragPx = 0f
                            }
                        }
                        .onFailure {
                            Toast.makeText(context, context.getString(R.string.error_failed_to_start_playback), Toast.LENGTH_SHORT).show()
                        }
                },
                MoreExecutors.directExecutor()
            )
        }
    }

    fun handleDemoMusicClick() {
        if (isDemoLoading) return

        if (demoFilesLoaded) {
            // Already loaded, just navigate
            selectedFolderKey = "assets_demo"
            selectedFolderName = context.getString(R.string.folder_demo_name)
            selectedFolderCoverUri = null
            return
        }

        // Load demo files
        isDemoLoading = true
        scope.launch {
            try {
                val demoFiles = queryDemoMidiFiles(context)

                // Remove any existing demo files first
                midiFiles.removeAll { it.folderKey == "assets_demo" }
                // Add new demo files
                midiFiles.addAll(demoFiles)

                demoFilesLoaded = true
                SettingsDataStore.setDemoFilesLoaded(context, true)
                isDemoLoading = false

                // Navigate to demo folder
                selectedFolderKey = "assets_demo"
                selectedFolderName = context.getString(R.string.folder_demo_name)
                selectedFolderCoverUri = null
            } catch (e: Exception) {
                isDemoLoading = false
                Toast.makeText(
                    context,
                    "Failed to load demo files: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun queueTrackNext(item: MidiFileItem, sourceItems: List<MidiFileItem>, sourceTitle: String?) {
        val service = playbackService
        if (service == null) {
            Toast.makeText(context, context.getString(R.string.error_playback_service_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        val queued = service.enqueueNextInQueue(
            uriString = item.uri.toString(),
            fallbackItems = sourceItems.map { it.uri.toString() },
            fallbackTitle = sourceTitle
        )
        if (queued) {
            Toast.makeText(context, context.getString(R.string.info_added_to_next_queue), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.info_no_active_queue), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(externalOpenUri, playbackService) {
        val uri = externalOpenUri ?: return@LaunchedEffect
        if (playbackService == null) return@LaunchedEffect
        handleMidiTap(uri)          // Route into existing playback flow
        onExternalOpenConsumed()    // Prevent duplicate playback
    }

    LaunchedEffect(selectedMidiFileUri) {
        val uri = selectedMidiFileUri ?: run {
            pianoRollData = null
            return@LaunchedEffect
        }
        Log.i("PlaybackPianoRollTS", "MainActivity selectedMidiFileUri changed: uri=$uri")
        pianoRollData = PianoRollData(
            notes = emptyList(),
            totalDurationMs = 0L,
            measurePositions = emptyList(),
            measureTickPositions = emptyList(),
            totalTicks = 0,
            tickTimeAnchors = emptyList()
        )
        val loaded = withContext(Dispatchers.Default) {
            runCatching {
                loadPianoRollDataFast(context, uri)
            }.getOrNull()
        }
        if (selectedMidiFileUri == uri && loaded != null) {
            pianoRollData = loaded
            Log.i(
                "PlaybackPianoRollTS",
                "MainActivity pianoRoll loaded: notes=${loaded.notes.size} totalTicks=${loaded.totalTicks} measureTicks=${loaded.measureTickPositions.size}"
            )
        } else if (loaded == null) {
            Log.e("PlaybackPianoRollTS", "MainActivity pianoRoll load failed: uri=$uri")
        }
    }

    // Focus requester for search bar
    val focusRequesterSearch = remember { FocusRequester() }
    val folderGridState = rememberLazyGridState()
    val animatedFolderKeys = remember { mutableSetOf<String>() }

    // Main screen start
    Box(modifier = modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)
                ),
                navigationIcon = {
                    if (selectedFolderKey != null || selectedPlaylistId != null) {
                        IconButton(
                            onClick = {
                                if (selectedPlaylistId != null) {
                                    selectedPlaylistId = null
                                    selectedPlaylistName = null
                                } else {
                                    selectedFolderKey = null
                                    selectedFolderName = null
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = stringResource(id = R.string.back)
                            )
                        }
                    }
                },
                title = {
                    if (selectedFolderKey != null || selectedPlaylistId != null) {
                        Text(
                            text = selectedFolderName ?: selectedPlaylistName ?: stringResource(id = R.string.unknown),
                            modifier = Modifier.fillMaxWidth()
                                .clipToBounds()
                                .basicMarquee(Int.MAX_VALUE),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.logo_image),
                            contentDescription = stringResource(id = R.string.app_logo),
                            modifier = Modifier.height(48.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) searchQuery = ""
                            if (isSearchActive) {
                                rootTab = RootTab.Browse
                            }
                        }
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isSearchActive) { MaterialTheme.colorScheme.primaryContainer } else { Color.Transparent },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(id = R.string.search),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(id = R.string.settings),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                },
                modifier = Modifier.zIndex(1f),
            )
        },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = selectedMidiFileUri != null,
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) +
                            fadeIn(animationSpec = tween(400)),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) +
                            fadeOut(animationSpec = tween(400))
                ) {
                    DraggableMiniPlayerContainer(
                        onExpand = {
                            skipNowPlayingEnterAnimation = true
                            showNowPlaying = true
                            miniLiftProgress = 0f
                            miniLiftDragPx = 0f
                        },
                        onDragProgress = { progress, dragPx ->
                            miniLiftProgress = progress
                            miniLiftDragPx = dragPx
                        }
                    ) {
                        MiniPlayerContainer(
                            playbackService = playbackService,
                            selectedMidiFileUri = selectedMidiFileUri,
                            onPlay = {
                                controllerFuture.get().play()
                                skipNowPlayingEnterAnimation = false
                                showNowPlaying = true
                                miniLiftProgress = 0f
                                miniLiftDragPx = 0f
                            },
                            onPause = { controllerFuture.get().pause() },
                            onSeekToMs = { ms -> controllerFuture.get().seekTo(ms) },
                            onPrevious = {
                                scope.launch {
                                    val shuffleEnabled = SettingsDataStore.shuffleEnabledFlow(context).first()
                                    val currentPositionMs = playbackService?.getCurrentPositionMs() ?: 0L
                                    if (currentPositionMs > 3000L) {
                                        controllerFuture.get().seekTo(0)
                                    } else {
                                        playbackService?.playPreviousInQueue(shuffleEnabled)
                                    }
                                }
                            },
                            onNext = {
                                scope.launch {
                                    val shuffleEnabled = SettingsDataStore.shuffleEnabledFlow(context).first()
                                    playbackService?.playNextInQueue(shuffleEnabled)
                                }
                            },
                            onExpandRequest = {
                                skipNowPlayingEnterAnimation = false
                                showNowPlaying = true
                            }
                        )
                    }
                }
                NavigationBar(
                    modifier = Modifier.height(100.dp)
                ) {
                    NavigationBarItem(
                        selected = rootTab == RootTab.Browse,
                        onClick = {
                            rootTab = RootTab.Browse
                            selectedPlaylistId = null
                            selectedPlaylistName = null
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(id = R.string.browse),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { Text(text = stringResource(id = R.string.browse)) }
                    )
                    NavigationBarItem(
                        selected = rootTab == RootTab.Playlists,
                        onClick = {
                            rootTab = RootTab.Playlists
                            isSearchActive = false
                            searchQuery = ""
                            selectedFolderKey = null
                            selectedFolderName = null
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = stringResource(id = R.string.playlists),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = { Text(text = stringResource(id = R.string.playlists)) }
                    )
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                AnimatedVisibility(
                    visible = isSearchActive,
                    enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(200)) +
                        fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(200)) +
                        fadeOut(animationSpec = tween(200))
                ) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .zIndex(0f)
                                .focusRequester(focusRequesterSearch),
                            placeholder = { Text(stringResource(id = R.string.topbar_search_summary)) },
                            singleLine = true
                        )
                        LaunchedEffect(Unit) {
                            focusRequesterSearch.requestFocus()
                        }
                    }
                }
                if (!hasAudioPermission) {
                    ElevatedButton(onClick = { storagePermissionLauncher.launch(permissionsToRequest) }) {
                        Text(stringResource(id = R.string.info_grant_storage_permission))
                    }
                } else if (midiFiles.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(id = R.string.info_no_mid_files_found),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))

                        if (isDemoLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(id = R.string.loading_demo_files),
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            DemoMusicButton(
                                onClick = { handleDemoMusicClick() },
                                showDividers = false
                            )
                        }
                    }
                } else {
                    val isSearching = isSearchActive && searchQuery.isNotBlank()
                    when {
                        rootTab == RootTab.Playlists && !isSearching && selectedFolderKey == null && selectedPlaylistId == null -> {
                            PlaylistHome(
                                repository = playlistRepository,
                                refreshToken = playlistRefreshToken,
                                onCreatePlaylist = { showCreatePlaylistDialog = true },
                                onOpenPlaylist = { summary ->
                                    selectedPlaylistId = summary.id
                                    selectedPlaylistName = summary.name
                                },
                                onPlayPlaylist = { summary ->
                                    scope.launch {
                                        val items = playlistRepository.getPlaylistItems(summary.id)
                                        val first = items.firstOrNull()?.uriString ?: return@launch
                                        val service = playbackService ?: return@launch
                                        if (!service.setActiveQueue(summary.id, first)) return@launch
                                        service.currentArtworkUri = null
                                        service.currentArtist = summary.name
                                        handleMidiTap(
                                            uri = Uri.parse(first),
                                            sourceItems = items.map {
                                                MidiFileItem(
                                                    uri = Uri.parse(it.uriString),
                                                    title = it.title.orEmpty(),
                                                    folderName = summary.name,
                                                    folderKey = "playlist_${summary.id}",
                                                    durationMs = it.durationMs
                                                )
                                            },
                                            sourceTitle = summary.name,
                                            configureTransientQueue = false
                                        )
                                    }
                                }
                            )
                        }
                        selectedPlaylistId != null -> {
                            PlaylistTracks(
                                repository = playlistRepository,
                                playlistId = selectedPlaylistId!!,
                                playlistName = selectedPlaylistName.orEmpty(),
                                selectedUri = selectedMidiFileUri,
                                onItemClick = { uriString, queue ->
                                    scope.launch {
                                        val service = playbackService ?: return@launch
                                        if (!service.setActiveQueue(selectedPlaylistId!!, uriString)) return@launch
                                        service.currentArtworkUri = null
                                        service.currentArtist = selectedPlaylistName
                                        handleMidiTap(
                                            uri = Uri.parse(uriString),
                                            sourceItems = queue,
                                            sourceTitle = selectedPlaylistName,
                                            configureTransientQueue = false
                                        )
                                    }
                                },
                                onQueueNext = { item, queue ->
                                    queueTrackNext(item, queue, selectedPlaylistName)
                                }
                            )
                        }
                        else -> {
                            val screenState: Pair<BrowseScreen, String?> = when {
                                isSearching -> BrowseScreen.Search to selectedFolderKey
                                selectedFolderKey == null -> BrowseScreen.Folders to null
                                else -> BrowseScreen.Files to selectedFolderKey
                            }
                            AnimatedContent(
                                targetState = screenState,
                                transitionSpec = {
                                    val target = targetState.first
                                    val initial = initialState.first
                                    when {
                                        target == BrowseScreen.Files && initial == BrowseScreen.Folders ->
                                            slideInHorizontally(
                                                initialOffsetX = { it },
                                                animationSpec = tween(220)
                                            ) + fadeIn(animationSpec = tween(120)) togetherWith
                                                slideOutHorizontally(
                                                    targetOffsetX = { -it },
                                                    animationSpec = tween(220)
                                                ) + fadeOut(animationSpec = tween(120))
                                        target == BrowseScreen.Folders && initial == BrowseScreen.Files ->
                                            slideInHorizontally(
                                                initialOffsetX = { -it },
                                                animationSpec = tween(220)
                                            ) + fadeIn(animationSpec = tween(120)) togetherWith
                                                slideOutHorizontally(
                                                    targetOffsetX = { it },
                                                    animationSpec = tween(220)
                                                ) + fadeOut(animationSpec = tween(120))
                                        else ->
                                            fadeIn(animationSpec = tween(120)) togetherWith
                                                fadeOut(animationSpec = tween(120))
                                    }
                                },
                                label = "BrowseContent"
                            ) { (screen, folderKey) ->
                                when (screen) {
                                    BrowseScreen.Folders -> FolderGrid(
                                        items = folderItems,
                                        gridState = folderGridState,
                                        animatedKeys = animatedFolderKeys,
                                        onFolderClick = { folder ->
                                            rootTab = RootTab.Browse
                                            selectedFolderKey = folder.key
                                            selectedFolderName = folder.name
                                            selectedFolderCoverUri = folder.coverUri
                                        },
                                        onDemoMusicClick = { handleDemoMusicClick() },
                                        isDemoLoading = isDemoLoading
                                    )
                                    BrowseScreen.Files -> {
                                        val items = midiFiles.filter { it.folderKey == folderKey }
                                        MidiFileList(
                                            items = items,
                                            selectedUri = selectedMidiFileUri,
                                            onItemClick = { tapped ->
                                                handleMidiTap(
                                                    uri = tapped,
                                                    sourceItems = items,
                                                    sourceTitle = selectedFolderName,
                                                    sourceCover = selectedFolderCoverUri
                                                )
                                            },
                                            onAddToPlaylist = { pendingPlaylistCandidate = it },
                                            onQueueNext = { queueTrackNext(it, items, selectedFolderName) }
                                        )
                                    }
                                    BrowseScreen.Search -> {
                                        val baseItems = if (folderKey != null) {
                                            midiFiles.filter { it.folderKey == folderKey }
                                        } else {
                                            midiFiles.toList()
                                        }
                                        val items = baseItems.filter {
                                            it.title.contains(searchQuery, ignoreCase = true)
                                        }
                                        MidiFileList(
                                            items = items,
                                            selectedUri = selectedMidiFileUri,
                                            onItemClick = { tapped ->
                                                handleMidiTap(
                                                    uri = tapped,
                                                    sourceItems = items,
                                                    sourceTitle = selectedFolderName,
                                                    sourceCover = selectedFolderCoverUri
                                                )
                                            },
                                            onAddToPlaylist = { pendingPlaylistCandidate = it },
                                            onQueueNext = { queueTrackNext(it, items, selectedFolderName) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (!showNowPlaying && selectedMidiFileUri != null && miniLiftProgress > 0f) {
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val screenPx = with(density) { configuration.screenHeightDp.dp.toPx() }
        val progress = miniLiftProgress.coerceIn(0f, 1f)
        val dragProgressByY = (miniLiftDragPx.coerceAtLeast(0f) / MINI_PLAYER_EXPAND_THRESHOLD_PX).coerceIn(0f, 1f)
        val followPx = screenPx * dragProgressByY
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
                .offset { IntOffset(0, (screenPx - followPx).roundToInt()) }
                .alpha(progress)
        ) {
            NowPlayingPianoRollSheet(
                fileUri = selectedMidiFileUri,
                playbackService = playbackService,
                pianoRollData = pianoRollData,
                onSeekToMs = { ms -> controllerFuture.get().seekTo(ms) },
                onPrevious = {
                    scope.launch {
                        val shuffleEnabled = SettingsDataStore.shuffleEnabledFlow(context).first()
                        val currentPositionMs = playbackService?.getCurrentPositionMs() ?: 0L
                        if (currentPositionMs > 3000L) {
                            controllerFuture.get().seekTo(0)
                        } else {
                            playbackService?.playPreviousInQueue(shuffleEnabled)
                        }
                    }
                },
                onNext = {
                    scope.launch {
                        val shuffleEnabled = SettingsDataStore.shuffleEnabledFlow(context).first()
                        playbackService?.playNextInQueue(shuffleEnabled)
                    }
                },
                onClose = { showNowPlaying = false }
            )
        }
    }
    if (showNowPlaying && selectedMidiFileUri != null) {
        DraggableNowPlayingContainer(
            onClose = {
                showNowPlaying = false
                skipNowPlayingEnterAnimation = false
            },
            animateIn = !skipNowPlayingEnterAnimation,
            modifier = Modifier.fillMaxSize().zIndex(3f)
        ) {
            NowPlayingPianoRollSheet(
                fileUri = selectedMidiFileUri,
                playbackService = playbackService,
                pianoRollData = pianoRollData,
                onSeekToMs = { ms -> controllerFuture.get().seekTo(ms) },
                onPrevious = {
                    scope.launch {
                        val shuffleEnabled = SettingsDataStore.shuffleEnabledFlow(context).first()
                        val currentPositionMs = playbackService?.getCurrentPositionMs() ?: 0L
                        if (currentPositionMs > 3000L) {
                            controllerFuture.get().seekTo(0)
                        } else {
                            playbackService?.playPreviousInQueue(shuffleEnabled)
                        }
                    }
                },
                onNext = {
                    scope.launch {
                        val shuffleEnabled = SettingsDataStore.shuffleEnabledFlow(context).first()
                        playbackService?.playNextInQueue(shuffleEnabled)
                    }
                },
                onClose = { showNowPlaying = false }
            )
        }
    }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name ->
                scope.launch {
                    runCatching {
                        playlistRepository.createPlaylist(name)
                    }.onSuccess {
                        playlistRefreshToken = System.currentTimeMillis()
                        showCreatePlaylistDialog = false
                    }
                }
            }
        )
    }

    if (!showCreatePlaylistDialog) pendingPlaylistCandidate?.let { candidate ->
        AddToPlaylistDialog(
            repository = playlistRepository,
            refreshToken = playlistRefreshToken,
            candidate = candidate,
            onDismiss = { pendingPlaylistCandidate = null },
            onCreatePlaylist = {
                showCreatePlaylistDialog = true
            },
            onAdd = { playlistId ->
                scope.launch {
                    playlistRepository.addItems(
                        playlistId = playlistId,
                        tracks = listOf(
                            PlaylistTrack(
                                uriString = candidate.uri.toString(),
                                title = candidate.title,
                                artist = candidate.folderName,
                                artworkUri = selectedFolderCoverUri?.toString(),
                                durationMs = candidate.durationMs,
                                position = 0
                            )
                        )
                    )
                    Toast.makeText(context, context.getString(R.string.info_added_to_playlist), Toast.LENGTH_SHORT).show()
                    playlistRefreshToken = System.currentTimeMillis()
                    pendingPlaylistCandidate = null
                }
            }
        )
    }

    if (showSoundFontDialog) {
        SoundFontDownloadDialog(
            onDismiss = { showSoundFontDialog = false },
            onDownloadComplete = { /* Optional callback if needed */ }
        )
    }
}

private data class NowPlayingRollUi(
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val loopStartMs: Long,
    val loopEndMs: Long
)

private const val MINI_PLAYER_EXPAND_THRESHOLD_PX = 1600f

@Composable
private fun DraggableMiniPlayerContainer(
    onExpand: () -> Unit,
    onDragProgress: (Float, Float) -> Unit,
    content: @Composable () -> Unit
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val expandThreshold = MINI_PLAYER_EXPAND_THRESHOLD_PX / 6
    var lastSentProgress by remember { mutableFloatStateOf(-1f) }
    var lastSentDragPx by remember { mutableFloatStateOf(-1f) }

    fun dispatchProgress(progress: Float, dragPx: Float, force: Boolean = false) {
        if (!force &&
            kotlin.math.abs(progress - lastSentProgress) < 0.007f &&
            kotlin.math.abs(dragPx - lastSentDragPx) < 1.5f
        ) return
        lastSentProgress = progress
        lastSentDragPx = dragPx
        onDragProgress(progress, dragPx)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        offsetY = (offsetY + dragAmount).coerceAtMost(0f)
                        dispatchProgress(((-offsetY) / expandThreshold).coerceIn(0f, 1f), -offsetY)
                    },
                    onDragEnd = {
                        if (offsetY < -expandThreshold) {
                            offsetY = 0f
                            dispatchProgress(0f, 0f, force = true)
                            onExpand()
                        } else {
                            scope.launch {
                                repeat(7) {
                                    offsetY += (0f - offsetY) * 0.45f
                                    dispatchProgress(((-offsetY) / expandThreshold).coerceIn(0f, 1f), -offsetY)
                                    delay(10)
                                }
                                offsetY = 0f
                                dispatchProgress(0f, 0f, force = true)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            repeat(7) {
                                offsetY += (0f - offsetY) * 0.45f
                                dispatchProgress(((-offsetY) / expandThreshold).coerceIn(0f, 1f), -offsetY)
                                delay(10)
                            }
                            offsetY = 0f
                            dispatchProgress(0f, 0f, force = true)
                        }
                    }
                )
            }
    ) {
        content()
    }
}

@Composable
private fun DraggableNowPlayingContainer(
    onClose: () -> Unit,
    animateIn: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxOffsetPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val scope = rememberCoroutineScope()
    var offsetY by remember { mutableFloatStateOf(maxOffsetPx) }

    LaunchedEffect(maxOffsetPx, animateIn) {
        if (animateIn) {
            offsetY = maxOffsetPx
            repeat(12) {
                offsetY += (0f - offsetY) * 0.38f
                delay(12)
            }
            offsetY = 0f
        } else {
            offsetY = 0f
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = (1f - (offsetY / maxOffsetPx).coerceIn(0f, 1f)) * 0.35f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.roundToInt()) }
                .pointerInput(maxOffsetPx) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            offsetY = (offsetY + dragAmount).coerceIn(0f, maxOffsetPx)
                        },
                        onDragEnd = {
                            if (offsetY > maxOffsetPx * 0.28f) {
                                offsetY = maxOffsetPx
                                onClose()
                            } else {
                                scope.launch {
                                    repeat(8) {
                                        offsetY += (0f - offsetY) * 0.42f
                                        delay(10)
                                    }
                                    offsetY = 0f
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                repeat(8) {
                                    offsetY += (0f - offsetY) * 0.42f
                                    delay(10)
                                }
                                offsetY = 0f
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}

@Composable
private fun NowPlayingPianoRollSheet(
    fileUri: Uri?,
    playbackService: PlaybackService?,
    pianoRollData: PianoRollData?,
    onSeekToMs: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val loopEnabled by SettingsDataStore.loopEnabledFlow(context).collectAsState(initial = false)
    val shuffleEnabled by SettingsDataStore.shuffleEnabledFlow(context).collectAsState(initial = false)

    if (fileUri == null) return
    var stablePositionMs by remember(fileUri) { mutableLongStateOf(0L) }
    var targetPositionMs by remember(fileUri) { mutableLongStateOf(0L) }
    val ui = produceState<NowPlayingRollUi?>(initialValue = null, key1 = playbackService, key2 = fileUri) {
        val service = playbackService ?: run { value = null; return@produceState }
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val lp = service.getLoopPoint()
            val duration = service.getDurationMs().coerceAtLeast(0L)
            val loopEnd = lp?.endMs?.takeIf { it > 0L }?.coerceIn(0L, duration) ?: duration
            value = NowPlayingRollUi(
                isPlaying = service.isPlaying(),
                positionMs = service.getCurrentPositionMs(),
                durationMs = duration,
                loopStartMs = lp?.startMs ?: 0L,
                loopEndMs = loopEnd
            )
            delay(24)
        }
    }.value

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

    LaunchedEffect(ui?.isPlaying, ui?.durationMs, ui?.loopStartMs, ui?.loopEndMs, targetPositionMs) {
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
    var isSeeking by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableStateOf(0.0f) }

    // derivedStateOf・亥ｱ謇蛹厄ｼ・
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
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val coverBitmap = rememberCoverBitmap(playbackService?.currentArtworkUri)
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
                        text = playbackService?.getCurrentTitle().orEmpty(),
                        Modifier
                            .padding(end = 16.dp)
                            .clipToBounds()
                            .basicMarquee(iterations = Int.MAX_VALUE),
                        maxLines = 1,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = playbackService?.currentArtist.orEmpty(),
                        Modifier
                            .padding(end = 16.dp)
                            .clipToBounds()
                            .basicMarquee(iterations = Int.MAX_VALUE),
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                    text = String.format("%d:%02d", minutes, remainingSeconds),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
                val durationText = ui?.durationMs ?: 0L
                Text(
                    text = String.format("%d:%02d", durationText / 60000, (durationText % 60000) / 1000),
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
                        scope.launch {
                            SettingsDataStore.setShuffleEnabled(context, !shuffleEnabled)
                        }
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
                            val service = playbackService ?: return@IconButton
                            if (ui?.isPlaying == true) service.pause() else service.play()
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
                        scope.launch {
                            SettingsDataStore.setLoopEnabled(context, !loopEnabled)
                        }
                    }
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (loopEnabled) { MaterialTheme.colorScheme.primaryContainer } else { Color.Transparent },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = Icons.Default.Loop,
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
    Log.d(
        "PlaybackPianoRollTS",
        "calculatePlaybackInitialZoomLevel: totalTicks=$totalTicks dominantMeasureTicks=$dominantMeasureTicks targetMeasures=$targetVisibleMeasures zoom=$zoom"
    )
    return zoom
}

@Composable
private fun MidiFileList(
    items: List<MidiFileItem>,
    selectedUri: Uri?,
    onItemClick: (Uri) -> Unit,
    onAddToPlaylist: (MidiFileItem) -> Unit,
    onQueueNext: (MidiFileItem) -> Unit
) {
    if (items.isEmpty()) {
        Text(
            text = stringResource(id = R.string.info_no_matching_files),
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 72.dp)
    ) {
        items(items, key = { it.uri }) { item ->
            MidiFileRow(
                item = item,
                isSelected = item.uri == selectedUri,
                onClick = { onItemClick(item.uri) },
                onAddToPlaylist = { onAddToPlaylist(item) },
                onQueueNext = { onQueueNext(item) }
            )
        }
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MidiFileRow(
    item: MidiFileItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onQueueNext: () -> Unit
) {
    val context = LocalContext.current
    var showActions by remember { mutableStateOf(false) }
    val background = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(0.25f)
    } else {
        Color.Transparent
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onBackground
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { showActions = true }
            )
            .padding(horizontal = 0.dp, vertical = 0.dp)
            .background(background, RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(16.dp)
        ) {
            Text(
                text = item.title,
                maxLines = 1,
                color = contentColor,
                modifier = Modifier.clipToBounds()
                    .basicMarquee(Int.MAX_VALUE)
            )
            if (item.folderName.isNotBlank()) {
                Text(
                    text = item.folderName,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.5f),
                    maxLines = 1,
                    color = contentColor
                )
            }
        }
        Text(
            text = formatDuration(item.durationMs),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
            color = contentColor
        )
    }

    if (showActions) {
        MidiFileActionsDialog(
            title = item.title,
            onDismiss = { showActions = false },
            onPlay = {
                showActions = false
                onQueueNext()
            },
            onShare = {
                showActions = false
                shareMidiFile(context, item.uri)
            },
            onDetails = {
                showActions = false
                val intent = Intent(context, FileDetailsActivity::class.java).apply {
                    putExtra(FileDetailsActivity.EXTRA_URI, item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            },
            onEditLoopPoint = {
                showActions = false
                val intent = Intent(context, EditLoopPointActivity::class.java).apply {
                    putExtra(EditLoopPointActivity.EXTRA_URI, item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            },
            onAddToPlaylist = {
                showActions = false
                onAddToPlaylist()
            }
        )
    }
}

@Composable
private fun MidiFileActionsDialog(
    title: String,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDetails: () -> Unit,
    onEditLoopPoint: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ElevatedButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = R.string.action_play_in_next_track))
                }
                ElevatedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = R.string.action_share))
                }
                ElevatedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.Info, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = R.string.action_details))
                }
                ElevatedButton(onClick = onAddToPlaylist, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.MusicNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = R.string.action_add_to_playlist))
                }
                /*ElevatedButton(onClick = onEditLoopPoint, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.LocationOn, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = R.string.action_edit_loop_point))
                }*/
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderGrid(
    items: List<FolderItem>,
    gridState: LazyGridState = rememberLazyGridState(),
    animatedKeys: MutableSet<String> = remember { mutableSetOf() },
    onFolderClick: (FolderItem) -> Unit,
    onDemoMusicClick: () -> Unit,
    isDemoLoading: Boolean = false
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, folder ->
            FolderCardAnimated(
                folder = folder,
                index = index,
                animatedKeys = animatedKeys,
                onClick = { onFolderClick(folder) }
            )
        }

        // Demo music button at bottom - spans full width
        item(
            span = { GridItemSpan(maxLineSpan) }
        ) {
            if (isDemoLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(id = R.string.loading_demo_files),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                DemoMusicButton(
                    onClick = onDemoMusicClick,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun FolderCardAnimated(
    folder: FolderItem,
    index: Int,
    animatedKeys: MutableSet<String>,
    onClick: () -> Unit
) {
    val alreadyAnimated = folder.key in animatedKeys
    var visible by remember(folder.key) { mutableStateOf(alreadyAnimated) }
    LaunchedEffect(folder.key) {
        if (!alreadyAnimated) {
            delay(0)
            visible = true
        }
        animatedKeys.add(folder.key)
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it / 8 },
            animationSpec = tween(0)
        ) + fadeIn(animationSpec = tween(200))
    ) {
        FolderCard(folder = folder, onClick = onClick)
    }
}

@Composable
private fun FolderCard(
    folder: FolderItem,
    onClick: () -> Unit
) {
    val coverBitmap = rememberCoverBitmap(folder.coverUri)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = folder.name,
                modifier = Modifier.padding(12.dp)
                    .clipToBounds()
                    .basicMarquee(Int.MAX_VALUE),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DemoMusicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDividers: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDividers) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        TextButton(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            onClick = onClick
        ) {
            Icon(
                imageVector = Icons.Filled.Audiotrack,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(stringResource(id = R.string.button_sample_demo_music))
        }
    }
}

@Composable
private fun PlaylistHome(
    repository: PlaylistRepository,
    refreshToken: Long,
    onCreatePlaylist: () -> Unit,
    onOpenPlaylist: (PlaylistSummary) -> Unit,
    onPlayPlaylist: (PlaylistSummary) -> Unit
) {
    val playlists = produceState(initialValue = emptyList<PlaylistSummary>(), refreshToken) {
        value = withContext(Dispatchers.IO) {
            repository.listPlaylists()
        }
    }.value

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            //Text(text = stringResource(id = R.string.playlists), style = MaterialTheme.typography.titleMedium)
            ElevatedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCreatePlaylist
            ) {
                Text(text = stringResource(id = R.string.action_create_playlist))
            }
        }
        if (playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = stringResource(id = R.string.info_no_playlists_found))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 72.dp)
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPlaylist(playlist) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = playlist.name, maxLines = 1)
                            Text(
                                text = "${playlist.itemCount} tracks",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.alpha(0.7f)
                            )
                        }
                        IconButton(onClick = { onPlayPlaylist(playlist) }) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTracks(
    repository: PlaylistRepository,
    playlistId: Long,
    playlistName: String,
    selectedUri: Uri?,
    onItemClick: (String, List<MidiFileItem>) -> Unit,
    onQueueNext: (MidiFileItem, List<MidiFileItem>) -> Unit
) {
    val context = LocalContext.current
    val items = produceState(initialValue = emptyList<MidiFileItem>(), playlistId) {
        value = withContext(Dispatchers.IO) {
            repository.getPlaylistItems(playlistId).map {
                val uri = Uri.parse(it.uriString)
                val duration = if (it.durationMs > 0L) it.durationMs else resolveDurationMsForUri(context, uri)
                MidiFileItem(
                    uri = uri,
                    title = it.title ?: uri.lastPathSegment.orEmpty(),
                    folderName = playlistName,
                    folderKey = "playlist_$playlistId",
                    durationMs = duration
                )
            }
        }
    }.value

    MidiFileList(
        items = items,
        selectedUri = selectedUri,
        onItemClick = { uri ->
            onItemClick(uri.toString(), items)
        },
        onAddToPlaylist = {},
        onQueueNext = { onQueueNext(it, items) }
    )
}

private fun resolveDurationMsForUri(context: Context, uri: Uri): Long {
    if (uri.scheme == "file") {
        val path = uri.path ?: return 0L
        val file = File(path)
        if (!file.exists() || !file.isFile) return 0L
        return calculateMidiDurationMs(file).coerceAtLeast(0L)
    }

    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DURATION),
            null,
            null,
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getLong(idx).coerceAtLeast(0L)
            }
        }
        0L
    }.getOrDefault(0L)
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    val focusRequesterPlaylistNameField = remember { FocusRequester() }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.action_create_playlist)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(stringResource(id = R.string.playlist_name_hint)) },
                modifier = Modifier.focusRequester(focusRequesterPlaylistNameField)
            )
            LaunchedEffect(Unit) {
                focusRequesterPlaylistNameField.requestFocus()
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank()
            ) {
                Text(text = stringResource(id = R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.cancel))
            }
        }
    )
}

@Composable
private fun AddToPlaylistDialog(
    repository: PlaylistRepository,
    refreshToken: Long,
    candidate: MidiFileItem,
    onDismiss: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onAdd: (Long) -> Unit
) {
    val playlists = produceState(initialValue = emptyList<PlaylistSummary>(), refreshToken) {
        value = withContext(Dispatchers.IO) {
            repository.listPlaylists()
        }
    }.value
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = candidate.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ElevatedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCreatePlaylist
                ) {
                    Text(text = stringResource(id = R.string.action_create_playlist))
                }
                if (playlists.isEmpty()) {
                    Text(text = stringResource(id = R.string.info_no_playlists_found))
                } else {
                    playlists.forEach { playlist ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onAdd(playlist.id) }
                        ) {
                            Text(text = playlist.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.cancel))
            }
        }
    )
}

private enum class BrowseScreen {
    Folders,
    Files,
    Search
}

private enum class RootTab {
    Browse,
    Playlists
}

private data class MidiFileItem(
    val uri: Uri,
    val title: String,
    val folderName: String,
    val folderKey: String,
    val durationMs: Long
)

private data class FolderItem(
    val key: String,
    val name: String,
    val coverUri: Uri?
)

private fun queryMidiFiles(context: Context): List<MidiFileItem> {
    val collection = MediaStore.Files.getContentUri("external")
    val projection = if (Build.VERSION.SDK_INT >= 29) {
        arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATA
        )
    } else {
        arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.DATA
        )
    }
    val selection = (
        "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR " +
            "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        )
    val selectionArgs = arrayOf(
        "audio/midi",
        "audio/x-midi",
        "%.mid",
        "%.midi"
    )
    val sortOrder = "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"

    val results = mutableListOf<MidiFileItem>()
    context.contentResolver.query(
        collection,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
        val relativePathColumn = if (Build.VERSION.SDK_INT >= 29) {
            cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
        } else {
            -1
        }
        val dataColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn) ?: continue
            val duration = cursor.getLong(durationColumn)
            val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn) else null
            val dataPath = if (dataColumn >= 0) cursor.getString(dataColumn) else null
            val folderKey = extractFolderKey(relativePath, dataPath)
            val folderName = extractFolderName(folderKey)
            val uri = ContentUris.withAppendedId(collection, id)
            results.add(MidiFileItem(uri, name, folderName, folderKey, duration))
        }
    }
    return results
}

private fun calculateMidiDurationMs(midiFile: File): Long {
    return try {
        midiFile.inputStream().use { inputStream ->
            val bytes = inputStream.readBytes().toList()
            val music = Midi1Music().apply { read(bytes) }

            // Find the maximum tick from all tracks
            var maxTick = 0
            for (track in music.tracks) {
                var tick = 0
                for (e in track.events) {
                    tick += e.deltaTime
                }
                if (tick > maxTick) {
                    maxTick = tick
                }
            }

            // Convert tick to milliseconds
            music.getTimePositionInMillisecondsForTick(maxTick).toLong()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        0L
    }
}

private suspend fun queryDemoMidiFiles(context: Context): List<MidiFileItem> = withContext(Dispatchers.IO) {
    val results = mutableListOf<MidiFileItem>()
    val demoFolderName = context.getString(R.string.folder_demo_name)

    try {
        val assetManager = context.assets
        val demoFiles = assetManager.list("demo") ?: emptyArray()

        for (fileName in demoFiles) {
            if (!fileName.endsWith(".mid", ignoreCase = true) &&
                !fileName.endsWith(".midi", ignoreCase = true)) {
                continue
            }

            // Copy asset to files directory (persistent storage) to get URI
            val cacheFile = File(context.filesDir, "demo/$fileName")
            if (!cacheFile.exists()) {
                cacheFile.parentFile?.mkdirs()
                assetManager.open("demo/$fileName").use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val uri = Uri.fromFile(cacheFile)

            // Calculate MIDI file duration using ktmidi
            val durationMs = calculateMidiDurationMs(cacheFile)

            results.add(MidiFileItem(
                uri = uri,
                title = fileName,
                folderName = demoFolderName,
                folderKey = "assets_demo",
                durationMs = durationMs
            ))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return@withContext results.sortedBy { it.title.lowercase() }
}

private fun extractFolderKey(relativePath: String?, dataPath: String?): String {
    relativePath?.let {
        val trimmed = it.trimEnd('/', '\\')
        if (trimmed.isNotBlank()) {
            return trimmed.replace('\\', '/')
        }
    }
    dataPath?.let {
        val normalized = it.replace('\\', '/')
        val parent = normalized.substringBeforeLast('/', "")
        if (parent.isNotBlank()) {
            return parent
        }
    }
    return ""
}

private fun extractFolderName(folderKey: String): String {
    if (folderKey.isBlank()) return ""
    return folderKey.substringAfterLast('/', folderKey.substringAfterLast('\\', folderKey))
}

private fun buildFolderItems(
    context: Context,
    items: List<MidiFileItem>,
    hasImagePermission: Boolean
): List<FolderItem> {
    val grouped = items.groupBy { it.folderKey }
    val results = mutableListOf<FolderItem>()
    for ((key, _) in grouped) {
        val name = extractFolderName(key).ifBlank { context.getString(R.string.unknown) }
        val coverUri = if (hasImagePermission) findCoverImageUri(context, key) else null
        results.add(FolderItem(key = key, name = name, coverUri = coverUri))
    }
    return results.sortedBy { it.name.lowercase() }
}

@Composable
private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return stringResource(id = R.string.duration_placeholder)
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

private fun shareMidiFile(context: Context, uri: Uri) {
    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, null))
}

private fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun findCoverImageUri(context: Context, folderKey: String): Uri? {
    if (folderKey.isBlank()) return null
    val collection = MediaStore.Files.getContentUri("external")
    val projection = if (Build.VERSION.SDK_INT >= 29) {
        arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DATA
        )
    } else {
        arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA
        )
    }
    val names = listOf(
        "cover.jpg",
        "cover.png",
        "folder.jpg",
        "folder.png",
        "Cover.jpg",
        "Cover.png"
    )

    val selection = if (Build.VERSION.SDK_INT >= 29) {
        val nameClause = names.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME}=?" }
        "${MediaStore.Files.FileColumns.RELATIVE_PATH}=? AND ($nameClause)"
    } else {
        val nameClause = names.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE ?" }
        "($nameClause)"
    }

    val selectionArgs = if (Build.VERSION.SDK_INT >= 29) {
        arrayOf(folderKey.trimEnd('/') + "/") + names.toTypedArray()
    } else {
        val base = folderKey.trimEnd('/')
        names.map { "%$base/$it" }.toTypedArray()
    }

    context.contentResolver.query(
        collection,
        projection,
        selection,
        selectionArgs,
        null
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(idColumn)
            return ContentUris.withAppendedId(collection, id)
        }
    }
    return null
}

@Composable
private fun rememberCoverBitmap(uri: Uri?): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    val bitmapState = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, uri) {
        if (uri == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val input: InputStream? = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }
            input?.use {
                val bmp = android.graphics.BitmapFactory.decodeStream(it)
                bmp?.asImageBitmap()
            }
        }
    }
    return bitmapState.value
}
