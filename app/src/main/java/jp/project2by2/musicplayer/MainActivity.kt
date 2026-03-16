package jp.project2by2.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.ClipData
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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ViewList
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
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.text.Normalizer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

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
        val midiMimeTypes = setOf(
            "audio/midi",
            "audio/mid",
            "audio/x-midi",
            "audio/x-mid",
            "audio/sp-midi",
            "application/midi",
            "application/x-midi"
        )
        val hasMidiExtension = path.endsWith(".mid") || path.endsWith(".midi")
        val isMidi = type in midiMimeTypes || (type == "application/octet-stream" && hasMidiExtension) || hasMidiExtension
        if (!isMidi) return

        externalOpenUri = uri
    }
}

const val STARTUP_TRACE_TAG = "StartupTrace"

private inline fun <T> logStartupStep(label: String, block: () -> T): T {
    var result: T? = null
    val durationMs = measureTimeMillis {
        result = block()
    }
    Log.d(STARTUP_TRACE_TAG, "$label took ${durationMs}ms")
    @Suppress("UNCHECKED_CAST")
    return result as T
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
    val midiParser = remember(context) { MidiParser(context.contentResolver) }
    val metadataCacheRepository = remember(context) { MidiMetadataCacheStore.repository(context) }

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
    var activePlaylistActions by remember { mutableStateOf<PlaylistSummary?>(null) }
    var renamePlaylistTarget by remember { mutableStateOf<PlaylistSummary?>(null) }
    var deletePlaylistTarget by remember { mutableStateOf<PlaylistSummary?>(null) }
    var openPlaylistInEditMode by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showNowPlayingActions by remember { mutableStateOf(false) }
    var folderViewModeOrdinal by rememberSaveable { mutableStateOf(0) }
    var folderViewMode by remember { mutableStateOf(FolderViewMode.Grid) }
    var pianoRollData by remember { mutableStateOf<PianoRollData?>(null) }
    var miniLiftProgress by remember { mutableFloatStateOf(0f) }
    var miniLiftDragPx by remember { mutableFloatStateOf(0f) }
    var skipNowPlayingEnterAnimation by remember { mutableStateOf(false) }

    // Playlist edit
    var isPlaylistEditModeActive by remember { mutableStateOf(false) }
    var playlistNameDraft by remember { mutableStateOf("") }
    var playlistEditOrderDraft by remember { mutableStateOf<List<MidiFileItem>>(emptyList()) }

    val midiFiles = remember { mutableStateListOf<MidiFileItem>() }
    val midiMetadataCache = remember { mutableStateMapOf<String, MidiMetadata>() }
    val midiAvailability = remember { mutableStateMapOf<String, MidiFileAvailability>() }
    val folderItems = remember { mutableStateListOf<FolderItem>() }
    val folderCoverCache = remember { mutableStateMapOf<String, Uri?>() }

    var isDemoLoading by remember { mutableStateOf(false) }
    var demoFilesLoaded by remember { mutableStateOf(false) }
    var isBrowseInitialLoading by remember { mutableStateOf(false) }
    var isBrowseRefreshing by remember { mutableStateOf(false) }
    var hasCompletedInitialBrowseLoad by remember { mutableStateOf(false) }
    var browseReadyUptimeMs by remember { mutableLongStateOf(0L) }
    var isFolderPreparing by remember { mutableStateOf(false) }
    var isSearchLoading by remember { mutableStateOf(false) }
    var browseAnimationToken by remember { mutableLongStateOf(0L) }
    val playlistRepository = remember(context) { PlaylistStore.repository(context) }
    val searchResults = remember { mutableStateListOf<MidiFileItem>() }

    fun applyFolderItems(items: List<MidiFileItem>) {
        val rebuilt = buildFolderItems(context, items, folderCoverCache.toMap())
        folderItems.clear()
        folderItems.addAll(rebuilt)
        val activeFolderKeys = rebuilt.map { folderItem: FolderItem -> folderItem.key }.toSet()
        folderCoverCache.entries.removeAll { entry: MutableMap.MutableEntry<String, Uri?> ->
            entry.key !in activeFolderKeys
        }
    }

    fun refreshSelectedFolderState() {
        val currentFolderKey = selectedFolderKey ?: return
        val folder = folderItems.firstOrNull { folderItem: FolderItem -> folderItem.key == currentFolderKey }
        if (folder == null && currentFolderKey != "assets_demo") {
            selectedFolderKey = null
            selectedFolderName = null
            selectedFolderCoverUri = null
            return
        }
        if (currentFolderKey == "assets_demo" && folder == null) {
            selectedFolderName = context.getString(R.string.folder_demo_name)
            selectedFolderCoverUri = null
            return
        }
        val resolvedFolder: FolderItem = requireNotNull(folder)
        selectedFolderName = resolvedFolder.name
        selectedFolderCoverUri = folderCoverCache[resolvedFolder.key] ?: resolvedFolder.coverUri
    }

    fun applyBrowseSnapshot(snapshot: BrowseLibrarySnapshot, animateItems: Boolean) {
        val previousItemsByUri = midiFiles.associateBy { item -> item.uri.toString() }
        midiFiles.clear()
        midiFiles.addAll(
            snapshot.midiFiles.map { item ->
                val uriKey = item.uri.toString()
                val previous = previousItemsByUri[uriKey]
                val cachedMetadata = midiMetadataCache[uriKey]
                when {
                    previous != null -> item.copy(
                        metadataTitle = previous.metadataTitle,
                        metadataArtist = previous.metadataArtist,
                        loopPointMs = previous.loopPointMs
                    )
                    cachedMetadata != null -> item.copy(
                        metadataTitle = cachedMetadata.title?.takeIf { it.isNotBlank() },
                        metadataArtist = cachedMetadata.copyright?.takeIf { it.isNotBlank() },
                        loopPointMs = cachedMetadata.loopPointMs
                    )
                    else -> item
                }
            }
        )
        midiAvailability.clear()
        folderItems.clear()
        folderItems.addAll(snapshot.folderItems)
        folderCoverCache.clear()
        folderCoverCache.putAll(snapshot.folderItems.associate { folderItem -> folderItem.key to folderItem.coverUri })
        refreshSelectedFolderState()
        if (animateItems) browseAnimationToken += 1L
    }

    fun applyMidiMetadata(uri: Uri, metadata: MidiMetadata) {
        val metadataTitle = metadata.title?.takeIf { it.isNotBlank() }
        val metadataArtist = metadata.copyright?.takeIf { it.isNotBlank() }
        val loopPointMs = metadata.loopPointMs
        val index = midiFiles.indexOfFirst { it.uri == uri }
        if (index < 0) return
        val current = midiFiles[index]
        if (
            current.metadataTitle == metadataTitle &&
            current.metadataArtist == metadataArtist &&
            current.loopPointMs == loopPointMs
        ) return
        midiFiles[index] = current.copy(
            metadataTitle = metadataTitle,
            metadataArtist = metadataArtist,
            loopPointMs = loopPointMs
        )
    }

    fun MidiFileItem.withMetadata(metadata: MidiMetadata?): MidiFileItem {
        if (metadata == null) return this
        return copy(
            metadataTitle = metadata.title?.takeIf { it.isNotBlank() } ?: metadataTitle,
            metadataArtist = metadata.copyright?.takeIf { it.isNotBlank() } ?: metadataArtist,
            loopPointMs = metadata.loopPointMs ?: loopPointMs
        )
    }

    fun mergeMetadataPreservingKnownValues(
        current: MidiMetadata?,
        incoming: MidiMetadata
    ): MidiMetadata {
        return MidiMetadata(
            title = current?.title?.takeIf { it.isNotBlank() } ?: incoming.title,
            copyright = current?.copyright?.takeIf { it.isNotBlank() } ?: incoming.copyright,
            loopPointMs = current?.loopPointMs ?: incoming.loopPointMs,
            durationMs = current?.durationMs ?: incoming.durationMs
        )
    }

    fun applyPersistedMetadata(entries: Map<String, MidiMetadata>) {
        if (entries.isEmpty()) return
        val mergedEntries = entries.mapValues { (uriString, incoming) ->
            mergeMetadataPreservingKnownValues(midiMetadataCache[uriString], incoming)
        }
        midiMetadataCache.putAll(mergedEntries)
        for (index in midiFiles.indices) {
            val item = midiFiles[index]
            val metadata = mergedEntries[item.uri.toString()] ?: continue
            midiFiles[index] = item.withMetadata(metadata)
        }
    }

    suspend fun updateMidiAvailability(
        item: MidiFileItem,
        forceRefresh: Boolean = false
    ): MidiFileAvailability {
        val key = item.uri.toString()
        val cached = midiAvailability[key]
        if (!forceRefresh && cached != null && cached != MidiFileAvailability.Unknown) {
            return cached
        }
        val status = withContext(Dispatchers.IO) {
            if (isMidiFileAccessible(context, item.uri)) {
                MidiFileAvailability.Available
            } else {
                MidiFileAvailability.Missing
            }
        }
        midiAvailability[key] = status
        return status
    }

    suspend fun prepareFolderContents(folderKey: String) {
        Log.d(STARTUP_TRACE_TAG, "prepareFolderContents start folderKey=$folderKey")
        val itemsInFolder = midiFiles.filter { item -> item.folderKey == folderKey }
        val uriStrings = itemsInFolder.map { it.uri.toString() }
        val persistedMetadata = logStartupStep("metadataCacheRepository.getByUris(folder)") {
            metadataCacheRepository.getByUris(uriStrings)
        }
        applyPersistedMetadata(persistedMetadata)
        for (item in itemsInFolder) {
            val key = item.uri.toString()
            if (midiMetadataCache[key] == null) {
                val metadata = logStartupStep("midiParser.getMetadata($key)") {
                    withContext(Dispatchers.IO) { midiParser.getMetadata(item.uri) }
                }
                midiMetadataCache[key] = metadata
                logStartupStep("metadataCacheRepository.put($key)") {
                    metadataCacheRepository.put(key, metadata)
                }
                applyMidiMetadata(item.uri, metadata)
            }
            logStartupStep("updateMidiAvailability($key)") {
                updateMidiAvailability(item, forceRefresh = true)
            }
        }
        Log.d(STARTUP_TRACE_TAG, "prepareFolderContents end folderKey=$folderKey items=${itemsInFolder.size}")
    }

    suspend fun performSearch(query: String, folderKey: String?) {
        val baseItems = if (folderKey != null) {
            midiFiles.filter { it.folderKey == folderKey }
        } else {
            midiFiles.toList()
        }
        val uriStrings = baseItems.map { it.uri.toString() }
        val persistedMetadata = metadataCacheRepository.getByUris(uriStrings)
        applyPersistedMetadata(persistedMetadata)

        val missingMetadataItems = baseItems.filter { item ->
            midiMetadataCache[item.uri.toString()] == null
        }
        val loadedMetadata = if (missingMetadataItems.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                missingMetadataItems.associate { item ->
                    item.uri.toString() to midiParser.getMetadata(item.uri)
                }
            }
        } else {
            emptyMap()
        }
        coroutineContext.ensureActive()

        loadedMetadata.forEach { (uriString, metadata) ->
            midiMetadataCache[uriString] = metadata
            applyMidiMetadata(Uri.parse(uriString), metadata)
        }
        metadataCacheRepository.putAll(loadedMetadata)

        val metadataSnapshot = midiMetadataCache.toMap()
        val availabilitySnapshot = midiAvailability.toMap()
        val matchedItems = withContext(Dispatchers.Default) {
            baseItems
                .map { item ->
                    item.withMetadata(
                        metadataSnapshot[item.uri.toString()] ?: loadedMetadata[item.uri.toString()]
                    )
                }
                .filter { item ->
                    item.matchesSearch(query) &&
                        availabilitySnapshot[item.uri.toString()] != MidiFileAvailability.Missing
                }
        }
        coroutineContext.ensureActive()

        searchResults.clear()
        searchResults.addAll(matchedItems)
    }

    fun clearSearchState() {
        isSearchLoading = false
        searchResults.clear()
    }

    fun handleMissingItem(item: MidiFileItem, listContext: MidiListContext) {
        val key = item.uri.toString()
        midiAvailability[key] = MidiFileAvailability.Missing
        if (listContext == MidiListContext.Playlist) {
            return
        }
        val index = midiFiles.indexOfFirst { it.uri == item.uri }
        if (index < 0) return
        midiFiles.removeAt(index)
        applyFolderItems(midiFiles)
        refreshSelectedFolderState()
    }

    suspend fun ensureItemAvailable(item: MidiFileItem, listContext: MidiListContext): Boolean {
        val availability = updateMidiAvailability(item)
        if (availability == MidiFileAvailability.Missing) {
            handleMissingItem(item, listContext)
            Toast.makeText(context, context.getString(R.string.error_midi_file_missing), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    // Media3
    val sessionToken = remember(context) {
        SessionToken(context, ComponentName(context, PlaybackService::class.java))
    }
    val controllerFuture = remember(context, sessionToken) {
        MediaController.Builder(context, sessionToken).buildAsync()
    }

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
    suspend fun refreshBrowseLibrary(isInitialLoad: Boolean) {
        if (!hasAudioPermission || isBrowseRefreshing) return
        if (isInitialLoad) {
            isBrowseInitialLoading = true
        }
        isBrowseRefreshing = true
        try {
            Log.d(
                STARTUP_TRACE_TAG,
                "refreshBrowseLibrary start initial=$isInitialLoad"
            )
            val snapshot = logStartupStep("loadBrowseLibrary") {
                loadBrowseLibrary(
                    context = context,
                    includeFolderCovers = hasImagePermission
                )
            }
            val persistedMetadata = logStartupStep("metadataCacheRepository.getByUris(initial)") {
                metadataCacheRepository.getByUris(
                    snapshot.midiFiles.map { it.uri.toString() }
                )
            }
            midiMetadataCache.putAll(persistedMetadata)
            applyBrowseSnapshot(snapshot, animateItems = true)
            hasCompletedInitialBrowseLoad = true
            if (browseReadyUptimeMs == 0L) {
                browseReadyUptimeMs = android.os.SystemClock.uptimeMillis()
            }
            Log.d(
                STARTUP_TRACE_TAG,
                "refreshBrowseLibrary end items=${snapshot.midiFiles.size} folders=${snapshot.folderItems.size} cachedMetadata=${persistedMetadata.size}"
            )
        } catch (_: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.error_failed_to_refresh_browse),
                Toast.LENGTH_SHORT
            ).show()
        } finally {
            isBrowseInitialLoading = false
            isBrowseRefreshing = false
        }
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

    DisposableEffect(controllerFuture) {
        onDispose {
            MediaController.releaseFuture(controllerFuture)
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
        refreshBrowseLibrary(isInitialLoad = !hasCompletedInitialBrowseLoad)
    }

    LaunchedEffect(isSearchActive, searchQuery, selectedFolderKey, browseAnimationToken) {
        if (!isSearchActive || searchQuery.isBlank()) {
            clearSearchState()
            return@LaunchedEffect
        }
        delay(180)
        isSearchLoading = true
        try {
            performSearch(
                query = searchQuery,
                folderKey = selectedFolderKey
            )
        } finally {
            if (isActive) {
                isSearchLoading = false
            }
        }
    }

    LaunchedEffect(selectedFolderKey, browseAnimationToken) {
        val folderKey = selectedFolderKey ?: return@LaunchedEffect
        if (browseAnimationToken == 0L) return@LaunchedEffect
        isFolderPreparing = true
        try {
            prepareFolderContents(folderKey)
        } finally {
            if (selectedFolderKey == folderKey) {
                isFolderPreparing = false
            }
        }
    }

    fun requestBrowseRefresh() {
        val now = android.os.SystemClock.uptimeMillis()
        val readyForManualRefresh = hasCompletedInitialBrowseLoad &&
            browseReadyUptimeMs > 0L &&
            now - browseReadyUptimeMs >= 1_500L &&
            !isFolderPreparing &&
            !isSearchLoading
        if (!readyForManualRefresh) {
            Log.d(
                STARTUP_TRACE_TAG,
                "ignored manual refresh ready=$hasCompletedInitialBrowseLoad uptimeDelta=${now - browseReadyUptimeMs} folderPreparing=$isFolderPreparing searchLoading=$isSearchLoading"
            )
            return
        }
        scope.launch {
            refreshBrowseLibrary(isInitialLoad = false)
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

    LaunchedEffect(Unit) {
        val savedMode = SettingsDataStore.folderViewModeFlow(context).first().coerceIn(0, 1)
        folderViewModeOrdinal = savedMode
        folderViewMode = if (savedMode == 1) FolderViewMode.List else FolderViewMode.Grid
    }

    LaunchedEffect(selectedPlaylistId) {
        isPlaylistEditModeActive = openPlaylistInEditMode && selectedPlaylistId != null
        playlistNameDraft = selectedPlaylistName.orEmpty()
        playlistEditOrderDraft = emptyList()
        openPlaylistInEditMode = false
    }

    // Back handler
    BackHandler(enabled = selectedPlaylistId != null || selectedFolderKey != null || isSearchActive) {
        when {
            isSearchActive -> {
                isSearchActive = false
                searchQuery = ""
                clearSearchState()
            }
            selectedPlaylistId != null -> {
                isPlaylistEditModeActive = false
                playlistNameDraft = ""
                playlistEditOrderDraft = emptyList()
                selectedPlaylistId = null
                selectedPlaylistName = null
            }
            selectedFolderKey != null -> {
                selectedFolderKey = null
                selectedFolderName = null
                isFolderPreparing = false
            }
        }
    }
    BackHandler(enabled = showNowPlaying) {
        showNowPlaying = false
    }

    fun handleMidiTap(
        item: MidiFileItem,
        listContext: MidiListContext,
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

        // Set artist and cover uri
        val service = playbackService
        if (service == null) {
            Toast.makeText(context, context.getString(R.string.error_playback_service_not_ready), Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            if (!ensureItemAvailable(item, listContext)) {
                return@launch
            }
            selectedMidiFileUri = item.uri
            if (configureTransientQueue) {
                val queueItems = sourceItems
                    ?.filter { midiAvailability[it.uri.toString()] != MidiFileAvailability.Missing }
                    ?.map { it.uri.toString() }
                    ?.ifEmpty { listOf(item.uri.toString()) }
                    ?: listOf(item.uri.toString())
                service.setTransientQueue(
                    items = queueItems,
                    title = sourceTitle ?: selectedFolderName,
                    startUri = item.uri.toString()
                )
            }
            service.currentArtist = sourceTitle ?: selectedFolderName
            service.currentArtworkUri = sourceCover ?: selectedFolderCoverUri
            val ok = withContext(Dispatchers.IO) {
                service.loadMidi(item.uri.toString())
            }
            if (!ok) {
                val status = updateMidiAvailability(item)
                if (status == MidiFileAvailability.Missing) {
                    handleMissingItem(item, listContext)
                    Toast.makeText(context, context.getString(R.string.error_midi_file_missing), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.error_failed_to_load_midi), Toast.LENGTH_SHORT).show()
                }
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

        selectedFolderKey = "assets_demo"
        selectedFolderName = context.getString(R.string.folder_demo_name)
        selectedFolderCoverUri = null

        if (demoFilesLoaded) {
            return
        }

        isDemoLoading = true
        scope.launch {
            try {
                val demoFiles = queryDemoMidiFiles(context)
                val demoMetadata = demoFiles.associate { item ->
                    item.uri.toString() to MidiMetadata(
                        title = item.metadataTitle,
                        copyright = item.metadataArtist,
                        loopPointMs = item.loopPointMs,
                        durationMs = item.durationMs
                    )
                }
                demoMetadata.forEach { (uriString, metadata) ->
                    midiMetadataCache[uriString] = metadata
                }
                metadataCacheRepository.putAll(demoMetadata)

                midiFiles.removeAll { it.folderKey == "assets_demo" }
                midiFiles.addAll(demoFiles)
                applyFolderItems(midiFiles)
                browseAnimationToken += 1L

                demoFilesLoaded = true
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Failed to load demo files: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isDemoLoading = false
            }
        }
    }

    fun queueTrackNext(item: MidiFileItem, sourceItems: List<MidiFileItem>, sourceTitle: String?) {
        val service = playbackService
        if (service == null) {
            Toast.makeText(context, context.getString(R.string.error_playback_service_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            if (!ensureItemAvailable(item, inferListContext(item))) {
                return@launch
            }
            val queued = service.enqueueNextInQueue(
                uriString = item.uri.toString(),
                fallbackItems = sourceItems
                    .filter { midiAvailability[it.uri.toString()] != MidiFileAvailability.Missing }
                    .map { it.uri.toString() },
                fallbackTitle = sourceTitle
            )
            if (queued) {
                Toast.makeText(context, context.getString(R.string.info_added_to_next_queue), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.info_no_active_queue), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(externalOpenUri, playbackService) {
        val uri = externalOpenUri ?: return@LaunchedEffect
        if (playbackService == null) return@LaunchedEffect
        val item = MidiFileItem(
            uri = uri,
            fileName = uri.lastPathSegment.orEmpty(),
            folderName = selectedFolderName.orEmpty(),
            folderKey = selectedFolderKey.orEmpty(),
            durationMs = 0L
        )
        handleMidiTap(item, MidiListContext.Browse) // Route into existing playback flow
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
    val folderListState = rememberLazyListState()

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
                                    isPlaylistEditModeActive = false
                                    playlistNameDraft = ""
                                    playlistEditOrderDraft = emptyList()
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
                        val topTitle = if (selectedPlaylistId != null) {
                            selectedPlaylistName
                        } else {
                            selectedFolderName
                        } ?: stringResource(id = R.string.unknown)
                        Text(
                            text = topTitle,
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
                    // Search button only should show on browse screen
                    if (rootTab == RootTab.Browse) {
                        IconButton(
                            onClick = {
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) {
                                    searchQuery = ""
                                    clearSearchState()
                                }
                                if (isSearchActive) {
                                    rootTab = RootTab.Browse
                                }
                            }
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (isSearchActive) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(id = R.string.search),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                    if (!isPlaylistEditModeActive) {
                        // Playlist edit start button
                        if (rootTab == RootTab.Playlists && selectedPlaylistId != null) {
                            IconButton(
                                onClick = {
                                    playlistNameDraft = selectedPlaylistName.orEmpty()
                                    playlistEditOrderDraft = emptyList()
                                    isPlaylistEditModeActive = true
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(id = R.string.action_edit),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                        // Settings button
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
                    } else {
                        // Playlist edit end button
                        IconButton(
                            onClick = {
                                val playlistId = selectedPlaylistId ?: return@IconButton
                                val draftName = playlistNameDraft.trim()
                                val currentName = selectedPlaylistName.orEmpty()
                                val orderedIds = playlistEditOrderDraft.mapNotNull { it.playlistItemId }
                                val shouldRename = draftName.isNotEmpty() && draftName != currentName
                                if (shouldRename) {
                                    // Reflect the new name immediately after finishing edit mode.
                                    selectedPlaylistName = draftName
                                }
                                scope.launch {
                                    if (shouldRename) {
                                        playlistRepository.renamePlaylist(playlistId, draftName)
                                    }
                                    if (orderedIds.isNotEmpty()) {
                                        playlistRepository.reorderPlaylistItems(playlistId, orderedIds)
                                    }
                                    playlistRefreshToken = System.currentTimeMillis()
                                    playlistNameDraft = selectedPlaylistName.orEmpty()
                                }
                                isPlaylistEditModeActive = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Done,
                                contentDescription = stringResource(id = R.string.action_done),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
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
                    modifier = Modifier
                ) {
                    NavigationBarItem(
                        selected = rootTab == RootTab.Browse,
                        onClick = {
                            rootTab = RootTab.Browse
                            isSearchActive = false
                            searchQuery = ""
                            clearSearchState()
                            selectedFolderKey = null
                            selectedFolderName = null
                            selectedFolderCoverUri = null
                            isPlaylistEditModeActive = false
                            playlistNameDraft = ""
                            playlistEditOrderDraft = emptyList()
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
                            isPlaylistEditModeActive = false
                            playlistNameDraft = ""
                            playlistEditOrderDraft = emptyList()
                            selectedPlaylistId = null
                            selectedPlaylistName = null
                            isSearchActive = false
                            searchQuery = ""
                            clearSearchState()
                            selectedFolderKey = null
                            selectedFolderName = null
                            selectedFolderCoverUri = null
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
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
                } else if (isBrowseInitialLoading && !hasCompletedInitialBrowseLoad) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (hasCompletedInitialBrowseLoad && midiFiles.isEmpty()) {
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

                        DemoMusicButton(
                            onClick = { handleDemoMusicClick() },
                            showDividers = false
                        )
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
                                onShowPlaylistActions = { summary ->
                                    activePlaylistActions = summary
                                },
                                onPlayPlaylist = { summary ->
                                    scope.launch {
                                        val items = playlistRepository.getPlaylistItems(summary.id)
                                        val first = items.firstOrNull()?.uriString ?: return@launch
                                        val firstItem = MidiFileItem(
                                            uri = Uri.parse(first),
                                            fileName = items.firstOrNull()?.title.orEmpty(),
                                            folderName = summary.name,
                                            folderKey = "playlist_${summary.id}",
                                            durationMs = items.firstOrNull()?.durationMs ?: 0L
                                        )
                                        if (!ensureItemAvailable(firstItem, MidiListContext.Playlist)) return@launch
                                        val service = playbackService ?: return@launch
                                        if (!service.setActiveQueue(summary.id, first)) return@launch
                                        service.currentArtworkUri = null
                                        service.currentArtist = summary.name
                                        handleMidiTap(
                                            item = firstItem,
                                            listContext = MidiListContext.Playlist,
                                            sourceItems = items.map {
                                                MidiFileItem(
                                                    uri = Uri.parse(it.uriString),
                                                    fileName = it.title.orEmpty(),
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
                                refreshToken = playlistRefreshToken,
                                isEditMode = isPlaylistEditModeActive,
                                selectedUri = selectedMidiFileUri,
                                availability = midiAvailability,
                                onEditItemsChanged = { editedItems ->
                                    playlistEditOrderDraft = editedItems
                                },
                                onPrimeItem = { item: MidiFileItem ->
                                    updateMidiAvailability(item, forceRefresh = true)
                                },
                                onMissingItemDetected = { item: MidiFileItem -> handleMissingItem(item, MidiListContext.Playlist) },
                                onItemClick = { uriString, queue ->
                                    scope.launch {
                                        val selectedItem = queue.firstOrNull { it.uri.toString() == uriString } ?: MidiFileItem(
                                            uri = Uri.parse(uriString),
                                            fileName = uriString.substringAfterLast('/'),
                                            folderName = selectedPlaylistName.orEmpty(),
                                            folderKey = "playlist_${selectedPlaylistId!!}",
                                            durationMs = 0L
                                        )
                                        if (!ensureItemAvailable(selectedItem, MidiListContext.Playlist)) return@launch
                                        val service = playbackService ?: return@launch
                                        if (!service.setActiveQueue(selectedPlaylistId!!, uriString)) return@launch
                                        service.currentArtworkUri = null
                                        service.currentArtist = selectedPlaylistName
                                        handleMidiTap(
                                            item = selectedItem,
                                            listContext = MidiListContext.Playlist,
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
                                    BrowseScreen.Folders -> BrowseRefreshContainer(
                                        isRefreshing = isBrowseRefreshing,
                                        onRefresh = { requestBrowseRefresh() }
                                    ) {
                                        FolderGrid(
                                            items = folderItems,
                                            folderCoverCache = folderCoverCache,
                                            gridState = folderGridState,
                                            animationToken = browseAnimationToken,
                                            onFolderClick = { folder: FolderItem ->
                                                val selectedFolder: FolderItem = folder
                                                rootTab = RootTab.Browse
                                                selectedFolderKey = selectedFolder.key
                                                selectedFolderName = selectedFolder.name
                                                selectedFolderCoverUri = folderCoverCache[selectedFolder.key] ?: selectedFolder.coverUri
                                            },
                                            onDemoMusicClick = { handleDemoMusicClick() },
                                            viewMode = folderViewMode,
                                            onViewModeChange = { mode ->
                                                folderViewMode = mode
                                                val persisted = if (mode == FolderViewMode.List) 1 else 0
                                                folderViewModeOrdinal = persisted
                                                scope.launch {
                                                    SettingsDataStore.setFolderViewMode(context, persisted)
                                                }
                                            },
                                            listState = folderListState
                                        )
                                    }
                                    BrowseScreen.Files -> {
                                        val items = midiFiles.filter {
                                            it.folderKey == folderKey &&
                                                midiAvailability[it.uri.toString()] != MidiFileAvailability.Missing
                                        }
                                    BrowseRefreshContainer(
                                        isRefreshing = isBrowseRefreshing,
                                        onRefresh = { requestBrowseRefresh() }
                                    ) {
                                            if (isFolderPreparing && items.isEmpty()) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    CircularProgressIndicator()
                                                }
                                            } else {
                                                MidiFileList(
                                                    items = items,
                                                    listContext = MidiListContext.Browse,
                                                    isLoading = isFolderPreparing || (folderKey == "assets_demo" && isDemoLoading),
                                                    selectedUri = selectedMidiFileUri,
                                                    availability = midiAvailability,
                                                    animationToken = browseAnimationToken,
                                                    onItemClick = { tapped: MidiFileItem ->
                                                        handleMidiTap(
                                                            item = tapped,
                                                            listContext = MidiListContext.Browse,
                                                            sourceItems = items,
                                                            sourceTitle = selectedFolderName,
                                                            sourceCover = selectedFolderCoverUri
                                                        )
                                                    },
                                                    onAddToPlaylist = { item: MidiFileItem -> pendingPlaylistCandidate = item },
                                                    onQueueNext = { item: MidiFileItem -> queueTrackNext(item, items, selectedFolderName) },
                                                    onMissingItemDetected = { item: MidiFileItem -> handleMissingItem(item, MidiListContext.Browse) }
                                                )
                                            }
                                        }
                                    }
                                    BrowseScreen.Search -> {
                                        BrowseRefreshContainer(
                                            isRefreshing = isBrowseRefreshing,
                                            onRefresh = { requestBrowseRefresh() }
                                        ) {
                                            MidiFileList(
                                                items = searchResults,
                                                listContext = MidiListContext.Search,
                                                isLoading = isSearchLoading,
                                                selectedUri = selectedMidiFileUri,
                                                availability = midiAvailability,
                                                animationToken = browseAnimationToken,
                                                onItemClick = { tapped: MidiFileItem ->
                                                    handleMidiTap(
                                                        item = tapped,
                                                        listContext = MidiListContext.Search,
                                                        sourceItems = searchResults.toList(),
                                                        sourceTitle = selectedFolderName,
                                                        sourceCover = selectedFolderCoverUri
                                                    )
                                                },
                                                onAddToPlaylist = { item: MidiFileItem -> pendingPlaylistCandidate = item },
                                                onQueueNext = { item: MidiFileItem ->
                                                    queueTrackNext(item, searchResults.toList(), selectedFolderName)
                                                },
                                                onMissingItemDetected = { item: MidiFileItem ->
                                                    handleMissingItem(item, MidiListContext.Search)
                                                }
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
                showActions = true,
                onActionsClick = { showNowPlayingActions = true },
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
                showActions = true,
                onActionsClick = { showNowPlayingActions = true },
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

    activePlaylistActions?.let { playlist ->
        PlaylistActionsDialog(
            title = playlist.name,
            onDismiss = { activePlaylistActions = null },
            onEdit = {
                activePlaylistActions = null
                openPlaylistInEditMode = true
                selectedPlaylistId = playlist.id
                selectedPlaylistName = playlist.name
            },
            onRename = {
                activePlaylistActions = null
                renamePlaylistTarget = playlist
            },
            onDelete = {
                activePlaylistActions = null
                deletePlaylistTarget = playlist
            }
        )
    }

    renamePlaylistTarget?.let { playlist ->
        RenamePlaylistDialog(
            initialName = playlist.name,
            onDismiss = { renamePlaylistTarget = null },
            onRename = { newName ->
                scope.launch {
                    playlistRepository.renamePlaylist(playlist.id, newName)
                    if (selectedPlaylistId == playlist.id) {
                        selectedPlaylistName = newName.trim()
                        playlistNameDraft = newName.trim()
                    }
                    playlistRefreshToken = System.currentTimeMillis()
                    renamePlaylistTarget = null
                }
            }
        )
    }

    deletePlaylistTarget?.let { playlist ->
        ConfirmDeletePlaylistDialog(
            playlistName = playlist.name,
            onDismiss = { deletePlaylistTarget = null },
            onConfirm = {
                scope.launch {
                    playlistRepository.deletePlaylist(playlist.id)
                    if (selectedPlaylistId == playlist.id) {
                        isPlaylistEditModeActive = false
                        playlistNameDraft = ""
                        playlistEditOrderDraft = emptyList()
                        selectedPlaylistId = null
                        selectedPlaylistName = null
                    }
                    playlistRefreshToken = System.currentTimeMillis()
                    deletePlaylistTarget = null
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
                                title = candidate.displayTitle(),
                                artist = candidate.metadataArtist ?: candidate.folderName,
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

    if (showNowPlayingActions && selectedMidiFileUri != null) {
        val currentUri = selectedMidiFileUri!!
        val currentTitle = playbackService?.getCurrentTitle()
            ?.takeIf { it.isNotBlank() }
            ?: currentUri.lastPathSegment?.substringAfterLast('/')
            ?: context.getString(R.string.unknown)
        MidiFileActionsDialog(
            title = currentTitle,
            onDismiss = { showNowPlayingActions = false },
            showPlayAction = false,
            loopEditEnabled = !DemoMidiContract.isDemoUri(currentUri),
            onPlay = {},
            onShare = {
                showNowPlayingActions = false
                shareMidiFile(context, currentUri)
            },
            onDetails = {
                showNowPlayingActions = false
                val intent = Intent(context, FileDetailsActivity::class.java).apply {
                    putExtra(FileDetailsActivity.EXTRA_URI, currentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            },
            onEditLoopPoint = {
                showNowPlayingActions = false
                val intent = Intent(context, EditLoopPointActivity::class.java).apply {
                    putExtra(EditLoopPointActivity.EXTRA_URI, currentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                context.startActivity(intent)
            },
            onAddToPlaylist = {
                showNowPlayingActions = false
                pendingPlaylistCandidate = MidiFileItem(
                    uri = currentUri,
                    fileName = currentTitle,
                    metadataTitle = currentTitle,
                    metadataArtist = playbackService?.currentArtist,
                    folderName = playbackService?.currentArtist.orEmpty(),
                    durationMs = playbackService?.getDurationMs() ?: 0L,
                    folderKey = ""
                )
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
private const val PLAYLIST_REORDER_DEBUG_LOG = true

private fun logPlaylistReorder(message: String) {
    if (PLAYLIST_REORDER_DEBUG_LOG) {
        Log.d("PlaylistReorder", message)
    }
}

@Composable
private fun DraggableMiniPlayerContainer(
    onExpand: () -> Unit,
    onDragProgress: (Float, Float) -> Unit,
    content: @Composable () -> Unit
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val expandThreshold = MINI_PLAYER_EXPAND_THRESHOLD_PX / 8
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
    showActions: Boolean = false,
    onActionsClick: () -> Unit = {},
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
                    val initialZoomLevel = remember(
                        pianoRollData.totalTicks,
                        pianoRollData.measureTickPositions
                    ) {
                        calculatePlaybackInitialZoomLevel(
                            totalTicks = pianoRollData.totalTicks,
                            measureTickPositions = pianoRollData.measureTickPositions
                        )
                    }
                    PlaybackPianoRollView(
                        notes = pianoRollData.notes,
                        measureTickPositions = pianoRollData.measureTickPositions,
                        tickTimeAnchors = pianoRollData.tickTimeAnchors,
                        currentPositionMs = stablePositionMs,
                        loopPointMs = ui.loopStartMs,
                        endPointMs = ui.loopEndMs,
                        totalDurationMs = maxOf(pianoRollData.totalDurationMs, ui.durationMs),
                        totalTicks = pianoRollData.totalTicks,
                        zoomLevel = initialZoomLevel,
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
    return (totalTicks.toFloat() / targetWindowTicks.toFloat()).coerceIn(1f, 40f)
}

@Composable
private fun MidiFileList(
    items: List<MidiFileItem>,
    listContext: MidiListContext,
    isLoading: Boolean = false,
    isEditMode: Boolean = false,
    onMoveItem: (Long, Int) -> Unit = { _, _ -> },
    onRemoveItem: (Long) -> Unit = {},
    selectedUri: Uri?,
    availability: Map<String, MidiFileAvailability> = emptyMap(),
    animationToken: Long = 0L,
    onItemClick: (MidiFileItem) -> Unit,
    onAddToPlaylist: (MidiFileItem) -> Unit,
    onQueueNext: (MidiFileItem) -> Unit,
    onMissingItemDetected: (MidiFileItem) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val defaultRowHeightPx = with(density) { 72.dp.toPx() }
    var measuredRowHeightPx by remember { mutableFloatStateOf(defaultRowHeightPx) }
    var activeDragItemId by remember { mutableStateOf<Long?>(null) }
    var activeDragOffsetPx by remember { mutableFloatStateOf(0f) }
    val removingItemIds = remember { mutableStateListOf<Long>() }

    if (items.isEmpty()) {
        if (isLoading) {
            LoadingMidiState()
        } else if (listContext == MidiListContext.Search) {
            EmptyMidiState(
                icon = Icons.Default.Search,
                message = stringResource(id = R.string.info_no_matching_files)
            )
        } else {
            EmptyMidiState(
                icon = Icons.Default.Folder,
                message = stringResource(id = R.string.info_no_mid_files_found)
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 72.dp)
    ) {
        lazyItemsIndexed(
            items = items,
            key = { index: Int, item: MidiFileItem -> item.playlistItemId ?: "${item.uri}#$index" }
        ) { index: Int, item: MidiFileItem ->
            val itemAvailability = availability[item.uri.toString()] ?: MidiFileAvailability.Unknown
            LaunchedEffect(item.uri, itemAvailability, listContext) {
                if (itemAvailability == MidiFileAvailability.Missing && listContext != MidiListContext.Playlist) {
                    onMissingItemDetected(item)
                }
            }
            val itemId = item.playlistItemId
            val activeId = activeDragItemId
            val activeIndex = if (activeId != null) {
                items.indexOfFirst { it.playlistItemId == activeId }
            } else {
                -1
            }
            val rowHeight = measuredRowHeightPx.coerceAtLeast(1f)
            val projectedShift = if (activeIndex >= 0) {
                (activeDragOffsetPx / rowHeight).roundToInt()
            } else {
                0
            }
            val projectedTargetIndex = if (activeIndex >= 0) {
                (activeIndex + projectedShift).coerceIn(0, items.lastIndex)
            } else {
                -1
            }
            val shiftTarget = when {
                activeIndex < 0 || index == activeIndex -> 0f
                activeIndex < projectedTargetIndex && index in (activeIndex + 1)..projectedTargetIndex -> -rowHeight
                activeIndex > projectedTargetIndex && index in projectedTargetIndex until activeIndex -> rowHeight
                else -> 0f
            }
            val animatedShift by animateFloatAsState(
                targetValue = shiftTarget,
                animationSpec = if (activeIndex >= 0) tween(durationMillis = 120) else snap(),
                label = "playlist_neighbor_shift"
            )

            StaggeredFadeInItem(
                itemKey = item.playlistItemId ?: item.uri.toString(),
                index = index,
                animationToken = animationToken,
                enabled = !isEditMode && listContext != MidiListContext.Playlist
            ) {
                MidiFileRow(
                    item = item,
                    availability = itemAvailability,
                    rowModifier = Modifier.graphicsLayer { translationY = animatedShift },
                    isSelected = item.uri == selectedUri,
                    showReorderHandle = isEditMode,
                    isRemoving = itemId != null && removingItemIds.contains(itemId),
                    onDragStart = {
                        if (!isEditMode || itemId == null) return@MidiFileRow
                        if (removingItemIds.contains(itemId)) return@MidiFileRow
                        activeDragItemId = itemId
                        activeDragOffsetPx = 0f
                        logPlaylistReorder("dragStart itemId=$itemId index=$index rowHeight=$rowHeight size=${items.size}")
                    },
                    onDragDelta = { dy ->
                        if (!isEditMode || activeDragItemId != itemId) return@MidiFileRow
                        activeDragOffsetPx += dy
                    },
                    onDragEnd = {
                        if (!isEditMode || itemId == null) return@MidiFileRow
                        val finalOffsetPx = activeDragOffsetPx
                        val fromIndex = items.indexOfFirst { it.playlistItemId == itemId }
                        if (fromIndex >= 0) {
                            val toIndex = (fromIndex + (finalOffsetPx / rowHeight).roundToInt())
                                .coerceIn(0, items.lastIndex)
                            val delta = toIndex - fromIndex
                            logPlaylistReorder(
                                "dragEnd itemId=$itemId from=$fromIndex to=$toIndex delta=$delta offsetPx=$finalOffsetPx rowHeight=$rowHeight"
                            )
                            if (delta != 0) {
                                activeDragOffsetPx = 0f
                                activeDragItemId = null
                                onMoveItem(itemId, delta)
                            } else {
                                activeDragOffsetPx = 0f
                                activeDragItemId = null
                            }
                        } else {
                            logPlaylistReorder("dragEnd itemId=$itemId but fromIndex not found")
                            activeDragOffsetPx = 0f
                            activeDragItemId = null
                        }
                    },
                    onDeleteClick = {
                        if (!isEditMode || itemId == null) return@MidiFileRow
                        if (removingItemIds.contains(itemId)) return@MidiFileRow
                        removingItemIds.add(itemId)
                        if (activeDragItemId == itemId) {
                            activeDragItemId = null
                            activeDragOffsetPx = 0f
                        }
                        scope.launch {
                            delay(220)
                            onRemoveItem(itemId)
                            removingItemIds.remove(itemId)
                        }
                    },
                    onRowHeightMeasured = { h ->
                        if (h > 1f) measuredRowHeightPx = h
                    },
                    onClick = { onItemClick(item) },
                    onAddToPlaylist = { onAddToPlaylist(item) },
                    onQueueNext = { onQueueNext(item) }
                )
            }
        }
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MidiFileRow(
    item: MidiFileItem,
    availability: MidiFileAvailability = MidiFileAvailability.Unknown,
    rowModifier: Modifier = Modifier,
    isSelected: Boolean,
    showReorderHandle: Boolean = false,
    isRemoving: Boolean = false,
    onDragStart: () -> Unit = {},
    onDragDelta: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onRowHeightMeasured: (Float) -> Unit = {},
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onQueueNext: () -> Unit
) {
    val context = LocalContext.current
    var showActions by remember { mutableStateOf(false) }
    var dragVisualOffsetPx by remember(item.playlistItemId, showReorderHandle) { mutableFloatStateOf(0f) }
    var isDragging by remember(item.playlistItemId, showReorderHandle) { mutableStateOf(false) }
    var rowWidthPx by remember(item.playlistItemId, showReorderHandle) { mutableFloatStateOf(0f) }
    val animatedScale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        animationSpec = spring(stiffness = 560f, dampingRatio = 0.9f),
        label = "playlist_drag_scale"
    )
    val dismissProgress by animateFloatAsState(
        targetValue = if (isRemoving) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "playlist_remove_progress"
    )
    val editLeadingWidth by animateDpAsState(
        targetValue = if (showReorderHandle) 44.dp else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "playlist_edit_leading_width"
    )
    val deleteButtonAlpha by animateFloatAsState(
        targetValue = if (showReorderHandle) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "playlist_delete_fade"
    )
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
    val titleAlpha = if (availability == MidiFileAvailability.Missing) 0.45f else 1f
    val secondaryText = if (availability == MidiFileAvailability.Missing) {
        context.getString(R.string.summary_midi_file_missing)
    } else {
        item.displaySecondaryText()
    }
    val secondaryColor = if (availability == MidiFileAvailability.Missing) {
        MaterialTheme.colorScheme.error
    } else {
        contentColor
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (dismissProgress > 0f) {
                    Color(0xFFC62828).copy(alpha = dismissProgress)
                } else {
                    Color.Transparent
                }
            )
            .onSizeChanged {
                rowWidthPx = it.width.toFloat()
                onRowHeightMeasured(it.height.toFloat())
            }
    ) {
        Row(
            modifier = rowModifier
                .fillMaxWidth()
                .zIndex(if (isDragging) 2f else 0f)
                .graphicsLayer {
                    translationX = -rowWidthPx * dismissProgress
                    translationY = dragVisualOffsetPx
                    scaleX = animatedScale
                    scaleY = animatedScale
                }
                .shadow(
                    elevation = if (isDragging) 8.dp else 0.dp,
                    shape = RoundedCornerShape(8.dp),
                    clip = false
                )
                .combinedClickable(
                    onClick = { if (!isRemoving) onClick() },
                    onLongClick = {
                        if (!showReorderHandle && !isRemoving) {
                            showActions = true
                        }
                    }
                )
                .then(
                    if (showReorderHandle && !isRemoving) {
                        Modifier.pointerInput(item.playlistItemId ?: item.uri) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    isDragging = true
                                    dragVisualOffsetPx = 0f
                                    onDragStart()
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val dy = dragAmount.y
                                    dragVisualOffsetPx += dy
                                    onDragDelta(dy)
                                },
                                onDragEnd = {
                                    dragVisualOffsetPx = 0f
                                    isDragging = false
                                    onDragEnd()
                                },
                                onDragCancel = {
                                    dragVisualOffsetPx = 0f
                                    isDragging = false
                                    onDragEnd()
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 0.dp, vertical = 0.dp)
                .background(background, RoundedCornerShape(4.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(editLeadingWidth)
                    .padding(start = if (showReorderHandle) 6.dp else 0.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showReorderHandle || deleteButtonAlpha > 0f) {
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier
                            .size(32.dp)
                            .graphicsLayer { alpha = deleteButtonAlpha }
                    ) {
                        Icon(
                            imageVector = Icons.Default.RemoveCircle,
                            contentDescription = null,
                            tint = Color(0xFFC62828)
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(16.dp)
            ) {
                Text(
                    text = item.displayTitle(),
                    maxLines = 1,
                    color = contentColor,
                    modifier = Modifier
                        .alpha(titleAlpha)
                        .clipToBounds()
                        .basicMarquee(Int.MAX_VALUE)
                )
                if (!secondaryText.isNullOrBlank()) {
                    Text(
                        text = secondaryText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(if (availability == MidiFileAvailability.Missing) 1f else 0.5f),
                        maxLines = 1,
                        color = secondaryColor
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.loopPointMs != null) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Text(
                    text = formatDuration(item.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                    color = contentColor
                )
            }
        }
    }

    if (showActions && !showReorderHandle) {
        MidiFileActionsDialog(
            title = item.displayTitle(),
            onDismiss = { showActions = false },
            loopEditEnabled = !DemoMidiContract.isDemoUri(item.uri),
            onPlay = {
                showActions = false
                if (availability == MidiFileAvailability.Missing) {
                    Toast.makeText(context, context.getString(R.string.error_midi_file_missing), Toast.LENGTH_SHORT).show()
                } else {
                    onQueueNext()
                }
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
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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
private fun LoadingMidiState() {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val upwardOffset = if (imeBottom > 0) (-96).dp else 0.dp

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.offset(y = upwardOffset)
        )
    }
}

@Composable
private fun EmptyMidiState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val upwardOffset = if (imeBottom > 0) (-96).dp else 0.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .offset(y = upwardOffset),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MidiFileActionsDialog(
    title: String,
    onDismiss: () -> Unit,
    showPlayAction: Boolean = true,
    loopEditEnabled: Boolean = true,
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
                if (showPlayAction) {
                    ElevatedButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(id = R.string.action_play_in_next_track))
                    }
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
                    Icon(imageVector = Icons.Default.QueueMusic, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = R.string.action_add_to_playlist))
                }
                ElevatedButton(
                    onClick = onEditLoopPoint,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = loopEditEnabled
                ) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = R.string.action_edit_loop_point))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderGrid(
    items: List<FolderItem>,
    folderCoverCache: MutableMap<String, Uri?>,
    gridState: LazyGridState = rememberLazyGridState(),
    listState: LazyListState = rememberLazyListState(),
    animationToken: Long = 0L,
    onFolderClick: (FolderItem) -> Unit,
    onDemoMusicClick: () -> Unit,
    viewMode: FolderViewMode,
    onViewModeChange: (FolderViewMode) -> Unit
) {
    var headerVisible by rememberSaveable { mutableStateOf(true) }

    val headerScrollBehavior = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                when {
                    available.y > 1f -> headerVisible = true   // 下方向スクロールで表示
                    available.y < -1f -> headerVisible = false // 上方向スクロールで非表示
                }
                return Offset.Zero
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .nestedScroll(headerScrollBehavior)
    ) {
        AnimatedVisibility(
            visible = headerVisible,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val folderCount = items.size
                Text(
                    text = "$folderCount folders",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = viewMode == FolderViewMode.Grid,
                        onClick = { onViewModeChange(FolderViewMode.Grid) },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {},
                        label = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(imageVector = Icons.Default.GridView, contentDescription = stringResource(id = R.string.view_grid))
                            }
                        }
                    )
                    SegmentedButton(
                        selected = viewMode == FolderViewMode.List,
                        onClick = { onViewModeChange(FolderViewMode.List) },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {},
                        label = {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(imageVector = Icons.Default.ViewList, contentDescription = stringResource(id = R.string.view_list))
                            }
                        }
                    )
                }
            }
        }

        if (viewMode == FolderViewMode.Grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                itemsIndexed(items, key = { _: Int, item: FolderItem -> item.key }) { index: Int, folder: FolderItem ->
                    StaggeredFadeInItem(
                        itemKey = folder.key,
                        index = index,
                        animationToken = animationToken
                    ) {
                        FolderCard(
                            folder = folder,
                            folderCoverCache = folderCoverCache,
                            onClick = { onFolderClick(folder) }
                        )
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    DemoMusicButton(
                        onClick = onDemoMusicClick,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        } else {
            FolderList(
                items = items,
                folderCoverCache = folderCoverCache,
                listState = listState,
                animationToken = animationToken,
                onFolderClick = onFolderClick,
                onDemoMusicClick = onDemoMusicClick
            )
        }
    }
}

@Composable
private fun FolderList(
    items: List<FolderItem>,
    folderCoverCache: MutableMap<String, Uri?>,
    listState: LazyListState = rememberLazyListState(),
    animationToken: Long = 0L,
    onFolderClick: (FolderItem) -> Unit,
    onDemoMusicClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 72.dp)
    ) {
        lazyItemsIndexed(items, key = { _: Int, item: FolderItem -> item.key }) { index: Int, folder: FolderItem ->
            StaggeredFadeInItem(
                itemKey = folder.key,
                index = index,
                animationToken = animationToken
            ) {
                FolderListRow(
                    folder = folder,
                    folderCoverCache = folderCoverCache,
                    onClick = { onFolderClick(folder) }
                )
            }
        }
        item {
            DemoMusicButton(
                onClick = onDemoMusicClick,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun FolderListRow(
    folder: FolderItem,
    folderCoverCache: MutableMap<String, Uri?>,
    onClick: () -> Unit
) {
    val coverBitmap = rememberFolderCoverBitmap(folder.key, folder.coverUri, folderCoverCache)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (coverBitmap != null) {
            Image(
                bitmap = coverBitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clipToBounds(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = folder.name,
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .basicMarquee(Int.MAX_VALUE),
            maxLines = 1
        )
    }
}

@Composable
private fun FolderCard(
    folder: FolderItem,
    folderCoverCache: MutableMap<String, Uri?>,
    onClick: () -> Unit
) {
    val coverBitmap = rememberFolderCoverBitmap(folder.key, folder.coverUri, folderCoverCache)
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
    onShowPlaylistActions: (PlaylistSummary) -> Unit,
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
                            .combinedClickable(
                                onClick = { onOpenPlaylist(playlist) },
                                onLongClick = { onShowPlaylistActions(playlist) }
                            )
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
private fun PlaylistActionsDialog(
    title: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ElevatedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.EditNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = R.string.action_edit))
                }
                ElevatedButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = R.string.action_rename))
                }
                ElevatedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(id = R.string.action_delete))
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

@Composable
private fun RenamePlaylistDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    val focusRequesterPlaylistNameField = remember { FocusRequester() }
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.action_rename_playlist)) },
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
                onClick = { onRename(name) },
                enabled = name.isNotBlank()
            ) {
                Text(text = stringResource(id = R.string.save))
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
private fun ConfirmDeletePlaylistDialog(
    playlistName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.action_delete_playlist)) },
        text = {
            Text(text = stringResource(id = R.string.confirm_delete_playlist_message, playlistName))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.action_delete))
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
private fun PlaylistTracks(
    repository: PlaylistRepository,
    playlistId: Long,
    playlistName: String,
    refreshToken: Long,
    isEditMode: Boolean,
    selectedUri: Uri?,
    availability: Map<String, MidiFileAvailability>,
    onEditItemsChanged: (List<MidiFileItem>) -> Unit,
    onPrimeItem: suspend (MidiFileItem) -> Unit,
    onMissingItemDetected: (MidiFileItem) -> Unit,
    onItemClick: (String, List<MidiFileItem>) -> Unit,
    onQueueNext: (MidiFileItem, List<MidiFileItem>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val midiParser = remember(context) { MidiParser(context.contentResolver) }
    val metadataCacheRepository = remember(context) { MidiMetadataCacheStore.repository(context) }
    val metadataCache = remember(playlistId) { mutableStateMapOf<String, MidiMetadata>() }
    val metadataLoading = remember(playlistId) { mutableStateMapOf<String, Boolean>() }
    val loadedItems = produceState(initialValue = emptyList<MidiFileItem>(), playlistId, refreshToken) {
        value = withContext(Dispatchers.IO) {
            repository.getPlaylistItems(playlistId).map {
                val uri = Uri.parse(it.uriString)
                val duration = if (it.durationMs > 0L) it.durationMs else resolveDurationMsForUri(context, uri)
                MidiFileItem(
                    playlistItemId = it.itemId,
                    uri = uri,
                    fileName = it.title ?: uri.lastPathSegment.orEmpty(),
                    folderName = playlistName,
                    folderKey = "playlist_$playlistId",
                    durationMs = duration
                )
            }
        }
    }.value
    val loadedItemsState = remember(playlistId) { mutableStateListOf<MidiFileItem>() }
    val editItems = remember(playlistId) { mutableStateListOf<MidiFileItem>() }
    var isEditItemsPrepared by remember(playlistId) { mutableStateOf(false) }

    fun applyMetadata(uri: Uri, metadata: MidiMetadata) {
        val metadataTitle = metadata.title?.takeIf { it.isNotBlank() }
        val metadataArtist = metadata.copyright?.takeIf { it.isNotBlank() }
        val loopPointMs = metadata.loopPointMs
        val loadedIndex = loadedItemsState.indexOfFirst { it.uri == uri }
        if (loadedIndex >= 0) {
            val current = loadedItemsState[loadedIndex]
            if (
                current.metadataTitle != metadataTitle ||
                current.metadataArtist != metadataArtist ||
                current.loopPointMs != loopPointMs
            ) {
                loadedItemsState[loadedIndex] = current.copy(
                    metadataTitle = metadataTitle,
                    metadataArtist = metadataArtist,
                    loopPointMs = loopPointMs
                )
            }
        }
        val editIndex = editItems.indexOfFirst { it.uri == uri }
        if (editIndex >= 0) {
            val current = editItems[editIndex]
            if (
                current.metadataTitle != metadataTitle ||
                current.metadataArtist != metadataArtist ||
                current.loopPointMs != loopPointMs
            ) {
                editItems[editIndex] = current.copy(
                    metadataTitle = metadataTitle,
                    metadataArtist = metadataArtist,
                    loopPointMs = loopPointMs
                )
                onEditItemsChanged(editItems.toList())
            }
        }
    }

    fun requestMetadata(item: MidiFileItem) {
        if (item.loopPointMs != null) return
        val key = item.uri.toString()
        metadataCache[key]?.let {
            applyMetadata(item.uri, it)
            return
        }
        if (metadataLoading[key] == true) return
        metadataLoading[key] = true
        scope.launch {
            val metadata = metadataCacheRepository.get(key)
                ?: withContext(Dispatchers.IO) { midiParser.getMetadata(item.uri) }
            metadataCache[key] = metadata
            metadataCacheRepository.put(key, metadata)
            applyMetadata(item.uri, metadata)
            metadataLoading.remove(key)
        }
    }

    LaunchedEffect(loadedItems) {
        val persistedMetadata = withContext(Dispatchers.IO) {
            metadataCacheRepository.getByUris(loadedItems.map { it.uri.toString() })
        }
        metadataCache.putAll(persistedMetadata)
        loadedItemsState.clear()
        loadedItemsState.addAll(
            loadedItems.map { item ->
                metadataCache[item.uri.toString()]?.let { metadata ->
                    item.copy(
                        metadataTitle = metadata.title?.takeIf { it.isNotBlank() },
                        metadataArtist = metadata.copyright?.takeIf { it.isNotBlank() },
                        loopPointMs = metadata.loopPointMs
                    )
                } ?: item
            }
        )
        loadedItemsState.forEach { item ->
            requestMetadata(item)
            onPrimeItem(item)
        }
        if (!isEditMode) {
            isEditItemsPrepared = false
        }
    }

    LaunchedEffect(playlistId, isEditMode, loadedItems) {
        if (isEditMode) {
            editItems.clear()
            editItems.addAll(loadedItemsState)
            isEditItemsPrepared = true
            onEditItemsChanged(editItems.toList())
        } else {
            isEditItemsPrepared = false
            onEditItemsChanged(emptyList())
        }
    }

    // Keep showing the loaded list until the edit buffer is ready to avoid a first-frame empty swap.
    val visibleItems = if (isEditMode && isEditItemsPrepared) editItems else loadedItemsState

    MidiFileList(
        items = visibleItems,
        listContext = MidiListContext.Playlist,
        isEditMode = isEditMode,
        onMoveItem = { itemId, delta ->
            if (!isEditMode) return@MidiFileList
            val fromIndex = editItems.indexOfFirst { it.playlistItemId == itemId }
            if (fromIndex < 0) return@MidiFileList
            val toIndex = (fromIndex + delta).coerceIn(0, editItems.lastIndex)
            if (toIndex == fromIndex) return@MidiFileList
            logPlaylistReorder("applyMove itemId=$itemId from=$fromIndex to=$toIndex delta=$delta")
            val moved = editItems.removeAt(fromIndex)
            editItems.add(toIndex, moved)
            onEditItemsChanged(editItems.toList())
        },
        onRemoveItem = { itemId ->
            if (!isEditMode) return@MidiFileList
            val idx = editItems.indexOfFirst { it.playlistItemId == itemId }
            if (idx < 0) return@MidiFileList
            logPlaylistReorder("removeItem itemId=$itemId at=$idx")
            editItems.removeAt(idx)
            onEditItemsChanged(editItems.toList())
        },
        selectedUri = selectedUri,
        availability = availability,
        onItemClick = { item ->
            onItemClick(item.uri.toString(), visibleItems)
        },
        onAddToPlaylist = {},
        onQueueNext = { onQueueNext(it, visibleItems) },
        onMissingItemDetected = onMissingItemDetected
    )
}

private fun resolveDurationMsForUri(context: Context, uri: Uri): Long {
    if (uri.scheme == "file") {
        val path = uri.path ?: return 0L
        val file = File(path)
        if (!file.exists() || !file.isFile) return 0L
        return calculateMidiDurationMs(file.readBytes()).coerceAtLeast(0L)
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
        title = { Text(text = candidate.displayTitle()) },
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

private enum class MidiListContext {
    Browse,
    Search,
    Playlist
}

private enum class MidiFileAvailability {
    Unknown,
    Available,
    Missing
}

private enum class RootTab {
    Browse,
    Playlists
}

private enum class FolderViewMode {
    Grid,
    List
}

private data class MidiFileItem(
    val playlistItemId: Long? = null,
    val uri: Uri,
    val fileName: String,
    val metadataTitle: String? = null,
    val metadataArtist: String? = null,
    val loopPointMs: Long? = null,
    val folderName: String,
    val folderKey: String,
    val durationMs: Long
)

private fun MidiFileItem.displayTitle(): String {
    return metadataTitle?.takeIf { it.isNotBlank() } ?: fileName
}

private fun MidiFileItem.displaySecondaryText(): String? {
    if (metadataTitle.isNullOrBlank()) {
        return folderName.takeIf { it.isNotBlank() }
    }
    val artist = metadataArtist?.takeIf { it.isNotBlank() }
    return if (artist != null) {
        "$fileName - $artist"
    } else {
        fileName
    }
}

private fun MidiFileItem.matchesSearch(query: String): Boolean {
    val queryTokens = query.searchTokens()
    if (queryTokens.isEmpty()) return true

    val searchableTexts = buildList {
        add(fileName.substringBeforeLast('.', missingDelimiterValue = fileName))
        add(fileName)
        metadataTitle?.let(::add)
        metadataArtist?.let(::add)
        add(folderName)
    }

    val normalizedTexts = searchableTexts.map { it.toSearchKey() }
    return queryTokens.all { token ->
        normalizedTexts.any { normalized ->
            normalized.contains(token)
        }
    }
}

private fun String.searchTokens(): List<String> {
    return toSearchKey()
        .split(' ')
        .filter { it.isNotBlank() }
}

private fun String.toSearchKey(): String {
    return Normalizer.normalize(this, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), " ")
        .trim()
}

private data class FolderItem(
    val key: String,
    val name: String,
    val coverUri: Uri?
)

private data class BrowseLibrarySnapshot(
    val midiFiles: List<MidiFileItem>,
    val folderItems: List<FolderItem>
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
            results.add(
                MidiFileItem(
                    uri = uri,
                    fileName = name,
                    folderName = folderName,
                    folderKey = folderKey,
                    durationMs = duration
                )
            )
        }
    }
    return results
}

private suspend fun loadBrowseLibrary(
    context: Context,
    includeFolderCovers: Boolean
): BrowseLibrarySnapshot = withContext(Dispatchers.IO) {
    val scannedFiles = logStartupStep("queryMidiFiles") {
        queryMidiFiles(context).toMutableList()
    }
    val sortedFiles = scannedFiles.sortedWith(
        compareBy<MidiFileItem>({ it.folderName.lowercase() }, { it.fileName.lowercase() })
    )
    val folderCoverUris = if (includeFolderCovers) {
        sortedFiles
            .asSequence()
            .map { it.folderKey }
            .filter { it.isNotBlank() }
            .distinct()
            .associateWith { folderKey ->
                findCoverImageUri(context, folderKey)
            }
    } else {
        emptyMap()
    }
    BrowseLibrarySnapshot(
        midiFiles = sortedFiles,
        folderItems = buildFolderItems(context, sortedFiles, folderCoverUris)
    )
}

private fun inferListContext(item: MidiFileItem): MidiListContext {
    return if (item.playlistItemId != null || item.folderKey.startsWith("playlist_")) {
        MidiListContext.Playlist
    } else {
        MidiListContext.Browse
    }
}

private fun calculateMidiDurationMs(midiBytes: ByteArray): Long {
    return try {
        val music = Midi1Music().apply { read(midiBytes.toList()) }

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
    } catch (e: Exception) {
        e.printStackTrace()
        0L
    }
}

private suspend fun queryDemoMidiFiles(context: Context): List<MidiFileItem> = withContext(Dispatchers.IO) {
    val results = mutableListOf<MidiFileItem>()
    val demoFolderName = context.getString(R.string.folder_demo_name)
    val midiParser = MidiParser(context.contentResolver)

    try {
        val assetManager = context.assets
        val demoFiles = assetManager.list("demo") ?: emptyArray()
        Log.d(STARTUP_TRACE_TAG, "queryDemoMidiFiles start count=${demoFiles.size}")

        for (fileName in demoFiles) {
            if (!fileName.endsWith(".mid", ignoreCase = true) &&
                !fileName.endsWith(".midi", ignoreCase = true)) {
                continue
            }

            val assetPath = "${DemoMidiContract.DEMO_FOLDER}/$fileName"
            val midiBytes = assetManager.open(assetPath).use { it.readBytes() }
            val uri = DemoMidiContract.buildUri(fileName)

            // Calculate MIDI file duration using ktmidi
            val metadata = logStartupStep("parseDemoMidiMetadata($fileName)") {
                midiParser.parseMetadata(midiBytes)
            }
            val durationMs = metadata.durationMs ?: logStartupStep("calculateMidiDurationMs($fileName)") {
                calculateMidiDurationMs(midiBytes)
            }

            results.add(MidiFileItem(
                uri = uri,
                fileName = fileName,
                metadataTitle = metadata.title?.takeIf { it.isNotBlank() },
                metadataArtist = metadata.copyright?.takeIf { it.isNotBlank() },
                loopPointMs = metadata.loopPointMs,
                folderName = demoFolderName,
                folderKey = "assets_demo",
                durationMs = durationMs
            ))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    Log.d(STARTUP_TRACE_TAG, "queryDemoMidiFiles end results=${results.size}")
    return@withContext results.sortedBy { it.fileName.lowercase() }
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
    folderCoverUris: Map<String, Uri?> = emptyMap()
): List<FolderItem> {
    val grouped = items
        .filterNot { it.folderKey == "assets_demo" }
        .groupBy { it.folderKey }
    val results = mutableListOf<FolderItem>()
    for ((key, _) in grouped) {
        val name = extractFolderName(key).ifBlank { context.getString(R.string.unknown) }
        results.add(FolderItem(key = key, name = name, coverUri = folderCoverUris[key]))
    }
    return results.sortedBy { it.name.lowercase() }
}

private fun isMidiFileAccessible(context: Context, uri: Uri): Boolean {
    return runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length != 0L || descriptor.fileDescriptor != null
        } ?: context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.recoverCatching {
        if (it is FileNotFoundException) {
            false
        } else {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        }
    }.getOrDefault(false)
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
        clipData = ClipData.newUri(context.contentResolver, "MIDI", uri)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = refreshState,
        modifier = Modifier.fillMaxSize()
    ) {
        content(this)
    }
}

@Composable
private fun StaggeredFadeInItem(
    itemKey: Any,
    index: Int,
    animationToken: Long,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    content()
}

@Composable
fun rememberCoverBitmap(uri: Uri?): androidx.compose.ui.graphics.ImageBitmap? {
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

@Composable
private fun rememberFolderCoverBitmap(
    folderKey: String,
    initialUri: Uri?,
    folderCoverCache: MutableMap<String, Uri?>
): androidx.compose.ui.graphics.ImageBitmap? {
    val resolvedUri = folderCoverCache[folderKey] ?: initialUri
    return rememberCoverBitmap(resolvedUri)
}
