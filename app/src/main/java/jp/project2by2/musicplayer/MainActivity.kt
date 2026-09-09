package jp.project2by2.musicplayer
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.NowPlayingState
import jp.project2by2.musicplayer.ui.browse.BrowseScreen
import jp.project2by2.musicplayer.ui.browse.TrackList
import jp.project2by2.musicplayer.ui.browse.DemoMusicButton
import jp.project2by2.musicplayer.ui.browse.MidiFileActionsDialog
import jp.project2by2.musicplayer.ui.playlist.*
import jp.project2by2.musicplayer.ui.player.NowPlayingSheet


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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
    var showSettings by rememberSaveable { mutableStateOf(false) }
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
            showSettings = false
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
    val folderGridState = rememberLazyGridState()
    val folderListState = rememberLazyListState()

    // The Android host supplies the same screens and service callbacks at both sizes.
    val playerContent: @Composable () -> Unit = {
        if (selectedMidiFileUri == null) {
            jp.project2by2.musicplayer.ui.player.EmptyNowPlayingPane()
        } else {
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
    jp.project2by2.musicplayer.ui.player.ResponsivePlayerLayout(
        showSettings = showSettings,
        // Compact Android keeps its existing draggable overlay inside the library.
        showPlayer = false,
        onBack = { wide ->
            if (showSettings) showSettings = false
            else if (!wide) showNowPlaying = false
        },
        settings = {
            BackHandler { showSettings = false }
            AndroidSettingsScreen(playbackService, onBack = { showSettings = false })
        },
        player = { playerContent() },
        modifier = modifier,
        library = { wide ->
    BackHandler(enabled = !wide && showNowPlaying) { showNowPlaying = false }
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            jp.project2by2.musicplayer.ui.player.PlayerTopAppBar(
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
                        jp.project2by2.musicplayer.ui.player.PlayerLogo()
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
                                if (wide) showSettings = true
                                else context.startActivity(Intent(context, SettingsActivity::class.java))
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
                    visible = !wide && selectedMidiFileUri != null,
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
                jp.project2by2.musicplayer.ui.player.PlayerNavigationBar(
                    playlistsSelected = rootTab == RootTab.Playlists,
                    onBrowse = {
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
                    onPlaylists = {
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
                        }
                )
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
                jp.project2by2.musicplayer.ui.browse.BrowseSearchField(
                    visible = isSearchActive, query = searchQuery, onQueryChange = { searchQuery = it }
                )
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
    if (!wide && !showNowPlaying && selectedMidiFileUri != null && miniLiftProgress > 0f) {
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
    if (!wide && showNowPlaying && selectedMidiFileUri != null) {
        DraggableNowPlayingContainer(
            onClose = {
                showNowPlaying = false
                skipNowPlayingEnterAnimation = false
            },
            animateIn = !skipNowPlayingEnterAnimation,
            modifier = Modifier.fillMaxSize().zIndex(3f)
        ) {
            playerContent()
        }
    }
    }

    })

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


    NowPlayingSheet(
        title = playbackService?.getCurrentTitle().orEmpty(), artist = playbackService?.currentArtist.orEmpty(),
        coverBitmap = rememberCoverBitmap(playbackService?.currentArtworkUri),
        ui = ui?.let { NowPlayingState(it.isPlaying, it.positionMs, it.durationMs, it.loopStartMs, it.loopEndMs) },
        pianoRollData = pianoRollData, loopEnabled = loopEnabled, shuffleEnabled = shuffleEnabled,
        onLoopChange = { scope.launch { SettingsDataStore.setLoopEnabled(context, it) } },
        onShuffleChange = { scope.launch { SettingsDataStore.setShuffleEnabled(context, it) } },
        showActions = showActions, onActionsClick = onActionsClick, onSeekToMs = onSeekToMs,
        onPrevious = onPrevious, onNext = onNext,
        onPlayPause = { playbackService?.let { if (it.isPlaying()) it.pause() else it.play() } },
        trackKey = fileUri
    )
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
    val context = LocalContext.current
    TrackList(
        items = items.map { it.toLibraryTrack() }, listContext = listContext,
        isLoading = isLoading, isEditMode = isEditMode, onMoveItem = onMoveItem,
        onRemoveItem = onRemoveItem, selectedUri = selectedUri?.toString(),
        availability = availability, animationToken = animationToken,
        onItemClick = { track -> items.find { it.uri.toString() == track.uri && it.playlistItemId == track.playlistItemId }?.let(onItemClick) },
        onAddToPlaylist = { track -> items.find { it.uri.toString() == track.uri && it.playlistItemId == track.playlistItemId }?.let(onAddToPlaylist) },
        onQueueNext = { track -> items.find { it.uri.toString() == track.uri && it.playlistItemId == track.playlistItemId }?.let(onQueueNext) },
        onMissingItemDetected = { track -> items.find { it.uri.toString() == track.uri && it.playlistItemId == track.playlistItemId }?.let(onMissingItemDetected) },
        actions = { track, close ->
            val item = items.first { it.uri.toString() == track.uri && it.playlistItemId == track.playlistItemId }
            val availability = availability[track.uri] ?: MidiFileAvailability.Unknown
        MidiFileActionsDialog(
            title = item.displayTitle(),
            onDismiss = { close() },
            loopEditEnabled = !DemoMidiContract.isDemoUri(item.uri),
            onPlay = {
                close()
                if (availability == MidiFileAvailability.Missing) {
                    Toast.makeText(context, context.getString(R.string.error_midi_file_missing), Toast.LENGTH_SHORT).show()
                } else {
                    onQueueNext(item)
                }
            },
            onShare = {
                close()
                shareMidiFile(context, item.uri)
            },
            onDetails = {
                close()
                val intent = Intent(context, FileDetailsActivity::class.java).apply {
                    putExtra(FileDetailsActivity.EXTRA_URI, item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            },
            onEditLoopPoint = {
                close()
                val intent = Intent(context, EditLoopPointActivity::class.java).apply {
                    putExtra(EditLoopPointActivity.EXTRA_URI, item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                context.startActivity(intent)
            },
            onAddToPlaylist = {
                close()
                onAddToPlaylist(item)
            }
        )
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
    BrowseScreen(
        items = items.map { LibraryFolder(it.key, it.name) },
        cover = { folder ->
            val original = items.first { it.key == folder.key }
            rememberFolderCoverBitmap(original.key, original.coverUri, folderCoverCache)
        },
        gridState = gridState, listState = listState, animationToken = animationToken,
        onFolderClick = { folder -> onFolderClick(items.first { it.key == folder.key }) },
        onDemoMusicClick = onDemoMusicClick, viewMode = viewMode, onViewModeChange = onViewModeChange
    )
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


    PlaylistScreen(
        playlists = playlists.map { LibraryPlaylist(it.id.toString(), it.name, it.itemCount) },
        onCreatePlaylist = onCreatePlaylist,
        onOpenPlaylist = { selected -> onOpenPlaylist(playlists.first { it.id.toString() == selected.id }) },
        onShowPlaylistActions = { selected -> onShowPlaylistActions(playlists.first { it.id.toString() == selected.id }) },
        onPlayPlaylist = { selected -> onPlayPlaylist(playlists.first { it.id.toString() == selected.id }) }
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

private enum class RootTab {
    Browse,
    Playlists
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

private fun MidiFileItem.displayTitle(): String = midiDisplayTitle(fileName, metadataTitle)

private fun MidiFileItem.displaySecondaryText(): String? = midiDisplaySecondaryText(fileName, folderName, metadataTitle, metadataArtist)

private fun MidiFileItem.matchesSearch(query: String): Boolean = matchesMidiSearch(fileName, metadataTitle, metadataArtist, folderName, query)

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
    val names = albumArtworkFileNames

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

private fun MidiFileItem.toLibraryTrack() = LibraryTrack(
    uri.toString(), displayTitle(), displaySecondaryText(), durationMs, playlistItemId, loopPointMs
)
