package jp.project2by2.musicplayer.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import jp.project2by2.musicplayer.*
import jp.project2by2.musicplayer.ios.audio.IosLoopPreview
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.NowPlayingState
import jp.project2by2.musicplayer.ui.browse.*
import jp.project2by2.musicplayer.ui.details.FileDetails
import jp.project2by2.musicplayer.ui.details.FileDetailsScreen
import jp.project2by2.musicplayer.ui.loop.LoopPointEditorScreen
import jp.project2by2.musicplayer.ui.player.*
import jp.project2by2.musicplayer.ui.playlist.*
import jp.project2by2.musicplayer.ui.settings.RecommendedSoundFontDialog
import jp.project2by2.musicplayer.ui.settings.SettingsScreen
import jp.project2by2.musicplayer.ui.settings.SoundFontLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Navigation and platform callbacks for iOS, arranged like the Android app: every screen is the
 * shared Android-derived Composable, and phone-only parts (player sheet, folder slide, dialogs,
 * details, loop editor) come from mobileMain. Settings, details and the loop editor slide in as
 * full screens where Android opens an Activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IosApp(controller: IosController) {
    val state by controller.state.collectAsState()
    val library = controller.library
    val settingsState = state.settings

    // SoundFont: offer the recommended banks on first start, as Android does.
    var recommendedFonts by remember { mutableStateOf(false) }
    var checkedInitialFont by remember { mutableStateOf(false) }
    LaunchedEffect(state.audioReady) {
        if (state.audioReady && !checkedInitialFont) {
            checkedInitialFont = true
            if (settingsState.soundFontFile == null) recommendedFonts = true
        }
    }
    SoundFontLoadingDialog(state.soundFontLoading && !recommendedFonts)
    if (recommendedFonts) RecommendedSoundFontDialog(
        onDismiss = { recommendedFonts = false },
        isDownloading = state.soundFontLoading,
        downloadProgress = state.soundFontDownloadProgress,
        onDownload = { option -> controller.downloadSoundFont(option) { success -> if (success) recommendedFonts = false } },
    )

    var playlistsSelected by remember { mutableStateOf(false) }
    var playlistId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var nowPlayingActions by remember { mutableStateOf(false) }
    var createPlaylist by remember { mutableStateOf(false) }
    var playlistActions by remember { mutableStateOf<IosPlaylist?>(null) }
    var renamePlaylist by remember { mutableStateOf<IosPlaylist?>(null) }
    var deletePlaylist by remember { mutableStateOf<IosPlaylist?>(null) }
    var unlinkFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var addTrack by remember { mutableStateOf<String?>(null) }
    // Android opens FileDetailsActivity / EditLoopPointActivity; here the shared screens slide in.
    var detailsFor by remember { mutableStateOf<String?>(null) }
    var loopEditFor by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var playlistNameDraft by remember { mutableStateOf("") }
    // The edited order of an open playlist (Android's edit buffer); null until something is edited.
    var playlistEditOrder by remember { mutableStateOf<List<String>?>(null) }
    val playlist = state.playlists.find { it.id == playlistId }
    LaunchedEffect(editing, playlistId) {
        if (editing) playlistNameDraft = playlist?.name.orEmpty()
        playlistEditOrder = null
    }
    val viewMode = if (settingsState.folderViewMode == 1) FolderViewMode.List else FolderViewMode.Grid

    // Playback that starts from a tap opens the player, like Android.
    var handledPlayerRequest by remember { mutableStateOf(state.showPlayerRequest) }
    LaunchedEffect(state.showPlayerRequest) {
        if (state.showPlayerRequest != handledPlayerRequest) {
            handledPlayerRequest = state.showPlayerRequest
            showSettings = false
            showNowPlaying = true
        }
    }

    val demoName = playerString("folder_demo_name")
    val dir = state.browseDir
    val demos = library.isDemo(dir)
    val searching = search && query.isNotBlank()
    fun folderName(key: String): String = if (library.isDemo(key)) demoName
        else library.parent(key)?.let(library::label).orEmpty()
    fun title(key: String) = midiDisplayTitle(library.fileName(key), state.metadata[key]?.title)
    val currentArtwork = rememberArtwork(library, state.current?.let(library::parent))

    // Pull to refresh reads the folders again (Android's Browse refresh).
    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val browseActions = BrowseActions(
        select = { key, queue, source -> showSettings = false; controller.select(key, queue, source) },
        queueNext = controller::queueNext,
        addToPlaylist = { addTrack = it },
        details = { detailsFor = it },
        editLoop = { loopEditFor = it },
        unlinkFolder = { unlinkFolder = it },
        refresh = {
            refreshing = true
            refreshScope.launch {
                controller.refreshLibrary()
                delay(600)
                refreshing = false
            }
        },
    )

    // Transient messages (Android's Toasts).
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message?.id) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message.text)
        controller.dismissMessage(message.id)
    }

    val playerSheet = rememberPlayerSheetState()
    LaunchedEffect(showNowPlaying) {
        if (playerSheet.targetExpanded != showNowPlaying) playerSheet.animateTo(showNowPlaying)
    }
    val settlePlayerSheet: (Boolean, Float) -> Unit = { expanded, velocityPx ->
        playerSheet.animateTo(expanded, velocityPx)
        showNowPlaying = expanded
    }

    val playerContent: @Composable (Boolean) -> Unit = { wide ->
        val current = state.current
        if (current == null) EmptyNowPlayingPane() else {
            val data = remember(state.index) {
                state.index?.let { PianoRollData(it.notes, it.totalDurationMs, it.measurePositionsMs, it.measureTickPositions, it.totalTicks, it.tickTimeAnchors) }
            }
            val metadata = state.metadata[current]
            NowPlayingSheet(
                title = title(current),
                artist = metadata?.copyright?.takeIf { it.isNotBlank() } ?: state.sourceTitle ?: folderName(current),
                coverBitmap = currentArtwork,
                ui = NowPlayingState(state.audio.playing, state.audio.positionMs, state.audio.durationMs,
                    metadata?.loopPointMs ?: 0L, state.audio.durationMs),
                pianoRollData = data, loopEnabled = settingsState.loopEnabled, shuffleEnabled = settingsState.shuffleEnabled,
                onLoopChange = controller::setLoop, onShuffleChange = controller::setShuffle,
                showActions = true, onActionsClick = { nowPlayingActions = true },
                onSeekToMs = controller::seek, onPrevious = controller::previous, onNext = controller::next,
                onPlayPause = controller::togglePlay, trackKey = current,
                onClose = if (wide) null else ({ showNowPlaying = false }),
                outputDelayMs = state.outputDelayMs + settingsState.pianoRollTimingMs,
            )
        }
    }

    val settingsContent: @Composable () -> Unit = {
        SettingsScreen(
            soundFontName = settingsState.soundFontName, hasSoundFont = settingsState.soundFontFile != null,
            soundFontLoading = state.soundFontLoading,
            maxVoices = settingsState.maxVoices, effectsEnabled = settingsState.effectsEnabled,
            reverbStrength = settingsState.reverbStrength,
            loopEnabled = settingsState.loopEnabled, shuffleEnabled = settingsState.shuffleEnabled,
            onBack = { showSettings = false },
            onPickSoundFont = { IosPlatformUi.pickSoundFont(controller::importSoundFont) },
            onRecommendedSoundFonts = { recommendedFonts = true },
            onMaxVoicesChange = controller::setMaxVoices, onEffectsChange = controller::setEffectsEnabled,
            onReverbChange = controller::setReverbStrength,
            onLoopChange = controller::setLoop, onShuffleChange = controller::setShuffle,
            onPianoRollTimingChange = controller::setPianoRollTiming,
            pianoRollTimingMs = settingsState.pianoRollTimingMs,
        )
    }

    fun closePlaylist() {
        playlistId = null
        editing = false
        playlistEditOrder = null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wide windows show Settings in the right pane; phones open it as its own screen like Android.
        val wideWindow = isWidePlayerLayout(maxWidth, maxHeight)
        ResponsivePlayerLayout(
            showSettings = showSettings && wideWindow,
            // The compact player is the draggable sheet inside the library, as on Android.
            showPlayer = false,
            onBack = { wide -> if (showSettings) showSettings = false else if (!wide) showNowPlaying = false },
            settings = settingsContent,
            player = { wide -> playerContent(wide) },
            library = { wide ->
                Box(Modifier.fillMaxSize().playerSheetHost(playerSheet)) {
                    Scaffold(
                        topBar = {
                            // Android's actions: search in Browse, edit for an open playlist, settings.
                            LibraryTopAppBar(
                                openPlaylistName = playlist?.name,
                                onClosePlaylist = { closePlaylist() },
                                editingName = playlistNameDraft.takeIf { editing && playlist != null },
                                onEditingNameChange = { playlistNameDraft = it },
                                actions = {
                                    if (!playlistsSelected) IconButton(onClick = { search = !search; query = "" }) {
                                        Surface(shape = RoundedCornerShape(50),
                                            color = if (search) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                                            Icon(Icons.Default.Search, playerString("search"), modifier = Modifier.padding(8.dp))
                                        }
                                    }
                                    if (playlist != null) IconButton(onClick = {
                                        if (editing) {
                                            // Done: rename and save the edited order (Android's edit mode).
                                            val name = playlistNameDraft.trim()
                                            if (name.isNotEmpty() && name != playlist.name) controller.renamePlaylist(playlist.id, name)
                                            playlistEditOrder?.let { controller.reorderPlaylist(playlist.id, it) }
                                        }
                                        editing = !editing
                                    }) {
                                        Icon(if (editing) Icons.Default.Done else Icons.Default.Edit,
                                            playerString(if (editing) "action_done" else "action_edit"), modifier = Modifier.padding(8.dp))
                                    }
                                    if (!editing) IconButton(onClick = { showSettings = true }) {
                                        Icon(Icons.Default.Settings, playerString("settings"), modifier = Modifier.padding(8.dp))
                                    }
                                })
                        },
                        bottomBar = {
                            Column {
                                MiniPlayerSheetBar(state = playerSheet, visible = !wide && state.current != null, onSettle = settlePlayerSheet) {
                                    state.current?.let { current ->
                                        MiniPlayerContent(title(current),
                                            state.metadata[current]?.copyright?.takeIf { it.isNotBlank() } ?: state.sourceTitle ?: folderName(current),
                                            currentArtwork, state.audio.playing,
                                            if (state.audio.durationMs > 0) state.audio.positionMs.toFloat() / state.audio.durationMs else 0f,
                                            formatPlayerDuration(state.audio.positionMs), controller::togglePlay) { showNowPlaying = true }
                                    }
                                }
                                PlayerNavigationBar(playlistsSelected,
                                    onBrowse = { playlistsSelected = false; closePlaylist(); search = false; query = "" },
                                    onPlaylists = { playlistsSelected = true; closePlaylist(); search = false; query = "" })
                            }
                        },
                    ) { padding ->
                        Column(Modifier.padding(padding).fillMaxSize()) {
                            if (!playlistsSelected) LibraryAddressBar(
                                folders = run {
                                    val levels = if (demos) listOf(library.home) else library.ancestry(dir)
                                    // A root offers the other roots, like Android's storage volumes.
                                    val roots = library.roots().map { AddressSegment("${it.id}:", it.label) }
                                    levels.map { level ->
                                        AddressSegment(level, library.label(level),
                                            choices = if (level == levels.first() && roots.size > 1 && !demos) roots else emptyList())
                                    }
                                },
                                demoOpen = demos,
                                onOpenFolder = { controller.browse(it) })
                            BrowseSearchField(visible = search, query = query, onQueryChange = { query = it })
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when {
                                    playlistsSelected && playlist == null -> PlaylistScreen(
                                        state.playlists.map { LibraryPlaylist(it.id, it.name, it.tracks.size) },
                                        onCreatePlaylist = { createPlaylist = true },
                                        onOpenPlaylist = { playlistId = it.id },
                                        onShowPlaylistActions = { item -> playlistActions = state.playlists.first { it.id == item.id } },
                                        onPlayPlaylist = { item -> controller.playPlaylist(item.id) })
                                    playlist != null -> key(playlist.id) {
                                        PlaylistPane(controller, state, playlist, editing, playlistEditOrder,
                                            onEditOrderChange = { playlistEditOrder = it }, actions = browseActions)
                                    }
                                    else -> BrowseFolderTransition(
                                        target = BrowseTarget(dir, searching),
                                        depth = { if (it.searching) 0 else browseDepth(library, it.dir) },
                                        sameScreen = { a, b -> a.searching == b.searching },
                                    ) { target ->
                                        BrowseRefreshContainer(isRefreshing = refreshing, onRefresh = browseActions.refresh) {
                                            BrowsePane(controller, state, target, query, viewMode, browseActions)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ExpandedPlayerSheet(state = playerSheet, visible = !wide && state.current != null,
                        expanded = showNowPlaying, onSettle = settlePlayerSheet) { playerContent(false) }
                }
            },
        )
        FullScreenPage(visible = showSettings && !wideWindow) { settingsContent() }
        // The last key stays while the page slides out.
        var detailsShown by remember { mutableStateOf<String?>(null) }
        if (detailsFor != null) detailsShown = detailsFor
        FullScreenPage(visible = detailsFor != null) {
            detailsShown?.let { key ->
                val details by produceState<FileDetails?>(null, key) {
                    value = withContext(Dispatchers.Default) { controller.fileDetails(key) }
                }
                FileDetailsScreen(details = details, onBack = { detailsFor = null })
            }
        }
        var loopEditShown by remember { mutableStateOf<String?>(null) }
        if (loopEditFor != null) loopEditShown = loopEditFor
        FullScreenPage(visible = loopEditFor != null) {
            loopEditShown?.let { key ->
                val preview = remember(key) { IosLoopPreview() }
                DisposableEffect(preview) { onDispose { preview.release() } }
                LoopPointEditorScreen(
                    key = key,
                    load = { withContext(Dispatchers.Default) { controller.loopEditorSource(key, preview) } },
                    export = { name, loopTick, endTick -> controller.exportLoopEdit(key, name, loopTick, endTick) },
                    onBack = { loopEditFor = null },
                    onSaved = { loopEditFor = null },
                )
            }
        }
        // Toast-like messages over every page, above the bottom bar and mini player.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).zIndex(20f)
            .windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 140.dp))
    }

    if (nowPlayingActions) state.current?.let { current ->
        TrackActions(controller, current, title(current), showPlay = false, close = { nowPlayingActions = false },
            actions = browseActions, onQueueNext = {})
    }
    unlinkFolder?.let { folder ->
        AlertDialog(onDismissRequest = { unlinkFolder = null },
            title = { Text(folder.name) },
            text = { Text(localized("Remove this folder from the app? The files stay where they are.",
                "このフォルダをアプリから外しますか？ フォルダ内のファイルは削除されません。")) },
            confirmButton = { TextButton(onClick = { controller.removeLinkedFolder(folder.key); unlinkFolder = null }) {
                Text(localized("Remove", "外す")) } },
            dismissButton = { TextButton(onClick = { unlinkFolder = null }) { Text(playerString("cancel")) } })
    }
    if (createPlaylist) CreatePlaylistDialog({ createPlaylist = false }, { controller.createPlaylist(it); createPlaylist = false })
    playlistActions?.let { selected ->
        PlaylistActionsDialog(selected.name, { playlistActions = null },
            { playlistId = selected.id; editing = true; playlistActions = null },
            { renamePlaylist = selected; playlistActions = null }, { deletePlaylist = selected; playlistActions = null })
    }
    renamePlaylist?.let { selected ->
        RenamePlaylistDialog(selected.name, { renamePlaylist = null }, { controller.renamePlaylist(selected.id, it); renamePlaylist = null })
    }
    deletePlaylist?.let { selected ->
        ConfirmDeletePlaylistDialog(selected.name, onDismiss = { deletePlaylist = null }, onConfirm = {
            controller.deletePlaylist(selected.id)
            if (playlistId == selected.id) closePlaylist()
            deletePlaylist = null
        })
    }
    if (!createPlaylist) addTrack?.let { key ->
        AddToPlaylistDialog(title(key), state.playlists.map { LibraryPlaylist(it.id, it.name, it.tracks.size) },
            onDismiss = { addTrack = null }, onCreatePlaylist = { createPlaylist = true },
            onAdd = { controller.addToPlaylist(key, it.id); addTrack = null })
    }
}

/** Host callbacks shared by the Browse and playlist panes. */
private class BrowseActions(
    val select: (key: String, queue: List<String>, sourceTitle: String?) -> Unit,
    val queueNext: (key: String, queue: List<String>, sourceTitle: String?) -> Unit,
    val addToPlaylist: (String) -> Unit,
    val details: (String) -> Unit,
    val editLoop: (String) -> Unit,
    val unlinkFolder: (LibraryFolder) -> Unit,
    val refresh: () -> Unit,
)

private data class BrowseTarget(val dir: String, val searching: Boolean)

/** Levels from the storage root, like Android: the start folder and linked folders are 1, samples 2. */
private fun browseDepth(library: IosLibrary, dir: String): Int =
    if (library.isDemo(dir)) 2 else library.ancestry(dir).size

/**
 * One folder (or the search below it). It reads its own folder, so the outgoing page keeps showing the
 * folder it belongs to while the explorer slide runs.
 */
@Composable
private fun BrowsePane(
    controller: IosController, state: IosState, target: BrowseTarget, query: String,
    viewMode: FolderViewMode, actions: BrowseActions,
) {
    val library = controller.library
    val dir = target.dir
    val demoName = playerString("folder_demo_name")
    fun folderName(key: String): String = if (library.isDemo(key)) demoName
        else library.parent(key)?.let(library::label).orEmpty()
    val listing by produceState<IosLibrary.Listing?>(null, dir, state.libraryRevision) {
        value = withContext(Dispatchers.Default) {
            runCatching { library.list(dir) }.getOrNull() ?: IosLibrary.Listing(dir, emptyList(), emptyList())
        }
    }
    val atHome = dir == library.home
    // Linked folders sit at the start folder, like the volumes Android offers there.
    val childFolders = remember(listing, atHome, state.libraryRevision) {
        val linked = if (atHome) library.linkedRoots().map { LibraryFolder("${it.id}:", it.label) } else emptyList()
        linked + listing?.folders.orEmpty().map { LibraryFolder(it, library.label(it)) }
    }
    // Search walks the open folder and everything below it, like Android and desktop.
    val searchFiles by produceState<List<String>?>(null, dir, target.searching, state.libraryRevision) {
        if (!target.searching) return@produceState
        value = withContext(Dispatchers.Default) {
            var latest = emptyList<String>()
            var shown = -1
            library.walk(dir, stop = { !isActive }) { batch ->
                latest = batch
                // Batch UI updates while a large folder tree is read.
                if (batch.size - shown >= 50) { value = batch; shown = batch.size }
            }
            latest
        }
    }
    val paths = remember(listing, searchFiles, state.metadata, target.searching, query) {
        if (target.searching) searchFiles.orEmpty().filter { key ->
            matchesMidiSearch(library.fileName(key), state.metadata[key]?.title, state.metadata[key]?.copyright, folderName(key), query)
        } else listing?.tracks.orEmpty()
    }
    LaunchedEffect(paths) { controller.loadMetadata(paths.take(500)) }
    LaunchedEffect(searchFiles) { searchFiles?.let { controller.loadMetadata(it.take(2000)) } }
    val tracks = remember(paths, state.metadata) { paths.map { key -> libraryTrack(controller, state, key, folderName(key)) } }
    val sourceTitle = if (library.isDemo(dir)) demoName else library.label(dir)
    val listState = remember(dir) { LazyListState() }

    val list: @Composable (PaddingValues) -> Unit = { headerPadding ->
        TrackList(tracks, contentPadding = headerPadding,
            listContext = if (target.searching) MidiListContext.Search else MidiListContext.Browse,
            isLoading = state.busy || (target.searching && searchFiles == null),
            selectedUri = state.current, listState = listState,
            onItemClick = { actions.select(it.uri, paths, sourceTitle) },
            onAddToPlaylist = { actions.addToPlaylist(it.uri) },
            onQueueNext = { actions.queueNext(it.uri, paths, sourceTitle) },
            actions = { track, close ->
                TrackActions(controller, track.uri, track.title, showPlay = true, close = close, actions = actions,
                    onQueueNext = { actions.queueNext(track.uri, paths, sourceTitle) })
            },
            hasDirectoryEntries = !target.searching && (childFolders.isNotEmpty() || atHome),
            directoryContent = {
                if (!target.searching) {
                    directoryEntries(childFolders, viewMode,
                        onFolder = { controller.browse(it.key) },
                        demo = if (atHome) ({ controller.browse("${IosLibrary.DEMO}:") }) else null,
                        cover = { folder -> rememberArtwork(library, folder.key) },
                        // Only folders linked from elsewhere have an action: removing the link.
                        onFolderActions = { folder -> if (library.isLinkedRoot(folder.key)) actions.unlinkFolder(folder) })
                    // iOS has no shared storage to browse: files come in through the Files app or these.
                    if (atHome) item(key = "ios:add") {
                        LibraryAddButtons(
                            onOpenFiles = { IosPlatformUi.pickMidiFiles(controller::openFiles) },
                            onAddFolder = { IosPlatformUi.pickFolder(controller::addLinkedFolder) })
                        if (childFolders.isEmpty() && tracks.isEmpty()) EmptyLibraryHint()
                    }
                }
            })
    }
    if (target.searching) list(PaddingValues()) else FolderBrowseHeader(folderBrowseSummary(childFolders.size, tracks.size), viewMode,
        onViewModeChange = { controller.setFolderViewMode(it == FolderViewMode.List) }) { padding ->
        if (listing != null) list(padding)
    }
}

/** An open playlist with Android's edit mode (reorder and remove, saved with Done). */
@Composable
private fun PlaylistPane(
    controller: IosController, state: IosState, playlist: IosPlaylist, editing: Boolean, editOrder: List<String>?,
    onEditOrderChange: (List<String>) -> Unit, actions: BrowseActions,
) {
    val library = controller.library
    val paths = if (editing) editOrder ?: playlist.tracks else playlist.tracks
    // Playlist songs whose file is gone are marked like Android ("File is missing").
    val availability by produceState(emptyMap<String, MidiFileAvailability>(), playlist.tracks, state.libraryRevision) {
        value = withContext(Dispatchers.Default) {
            playlist.tracks.associateWith { if (library.isAvailable(it)) MidiFileAvailability.Available else MidiFileAvailability.Missing }
        }
    }
    LaunchedEffect(paths) { controller.loadMetadata(paths.take(500)) }
    val demoName = playerString("folder_demo_name")
    val tracks = remember(paths, state.metadata) {
        paths.map { key -> libraryTrack(controller, state, key, if (library.isDemo(key)) demoName else library.parent(key)?.let(library::label).orEmpty()) }
    }
    val playable = paths.filter { availability[it] != MidiFileAvailability.Missing }
    TrackList(tracks, listContext = MidiListContext.Playlist, isLoading = state.busy,
        isEditMode = editing, selectedUri = state.current, availability = availability,
        onMoveItem = { id, delta ->
            val order = paths.toMutableList()
            val from = order.indexOfFirst { controller.trackId(it) == id }
            if (from >= 0) {
                order.add((from + delta).coerceIn(0, order.lastIndex), order.removeAt(from))
                onEditOrderChange(order)
            }
        },
        onRemoveItem = { id -> onEditOrderChange(paths.filterNot { controller.trackId(it) == id }) },
        // The queue skips songs known to be missing, as on Android.
        onItemClick = { controller.select(it.uri, playable, playlist.name, playlist.id) },
        onAddToPlaylist = { actions.addToPlaylist(it.uri) },
        onQueueNext = { actions.queueNext(it.uri, playable, playlist.name) },
        actions = { track, close ->
            TrackActions(controller, track.uri, track.title, showPlay = true, close = close, actions = actions,
                onQueueNext = { actions.queueNext(track.uri, playable, playlist.name) })
        })
}

private fun libraryTrack(controller: IosController, state: IosState, key: String, folderName: String): LibraryTrack {
    val library = controller.library
    val metadata = state.metadata[key]
    return LibraryTrack(key, midiDisplayTitle(library.fileName(key), metadata?.title),
        midiDisplaySecondaryText(library.fileName(key), folderName, metadata?.title, metadata?.copyright),
        metadata?.durationMs ?: 0, controller.trackId(key), metadata?.loopPointMs)
}

/** The song actions of Android's MidiFileActionsDialog. */
@Composable
private fun TrackActions(
    controller: IosController, key: String, title: String, showPlay: Boolean, close: () -> Unit,
    actions: BrowseActions, onQueueNext: () -> Unit,
) {
    MidiFileActionsDialog(title = title, onDismiss = close, showPlayAction = showPlay,
        loopEditEnabled = !controller.library.isDemo(key),
        onPlay = { close(); onQueueNext() },
        onShare = { close(); controller.library.resolve(key)?.let(IosPlatformUi::share) },
        onDetails = { close(); actions.details(key) },
        onEditLoopPoint = { close(); actions.editLoop(key) },
        onAddToPlaylist = { close(); actions.addToPlaylist(key) })
}

/** A page over the whole window that slides in from the right, where Android starts an Activity. */
@Composable
private fun BoxScope.FullScreenPage(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.matchParentSize().zIndex(10f),
        enter = slideInHorizontally(animationSpec = tween(250)) { it } + fadeIn(tween(250)),
        exit = slideOutHorizontally(animationSpec = tween(250)) { it } + fadeOut(tween(250)),
    ) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

/** Under the sample button at the start folder, in the same style. */
@Composable
private fun LibraryAddButtons(onOpenFiles: () -> Unit, onAddFolder: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = onOpenFiles, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(playerString("ios_open_files"))
        }
        TextButton(onClick = onAddFolder, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(playerString("ios_add_folder"))
        }
    }
}

@Composable
private fun EmptyLibraryHint() {
    Text(playerString("ios_files_hint"), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp))
}

/** Folder artwork for [dir], decoded off the main thread and cached per folder. */
@Composable
fun rememberArtwork(library: IosLibrary, dir: String?): ImageBitmap? = key(dir) {
    val image by produceState<ImageBitmap?>(artworkCache[dir], dir) {
        if (dir == null || dir in artworkCache) return@produceState
        val loaded = withContext(Dispatchers.Default) { runCatching { library.artwork(dir) }.getOrNull() }
        artworkCache[dir] = loaded
        value = loaded
    }
    image
}

/** The most recently decoded folder covers (null: the folder has none). */
private object ArtworkCache {
    private val entries = LinkedHashMap<String?, ImageBitmap?>()
    operator fun contains(key: String?) = entries.containsKey(key)
    operator fun get(key: String?) = entries[key]
    operator fun set(key: String?, value: ImageBitmap?) {
        entries.remove(key)
        if (entries.size >= 48) entries.remove(entries.keys.first())
        entries[key] = value
    }
}

private val artworkCache = ArtworkCache
