package jp.project2by2.musicplayer.ios

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
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.NowPlayingState
import jp.project2by2.musicplayer.ui.browse.*
import jp.project2by2.musicplayer.ui.details.FileDetails
import jp.project2by2.musicplayer.ui.details.FileDetailsScreen
import jp.project2by2.musicplayer.ui.player.*
import jp.project2by2.musicplayer.ui.playlist.*
import jp.project2by2.musicplayer.ui.settings.RecommendedSoundFontDialog
import jp.project2by2.musicplayer.ui.settings.SettingsScreen
import jp.project2by2.musicplayer.ui.settings.SoundFontLoadingDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Navigation and platform callbacks for iOS. Every screen is the shared Android-derived Composable;
 * compact windows use the same draggable player sheet as Android (mobileMain).
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
    var trackActionsFor by remember { mutableStateOf<String?>(null) }
    var createPlaylist by remember { mutableStateOf(false) }
    var playlistActions by remember { mutableStateOf<IosPlaylist?>(null) }
    var renamePlaylist by remember { mutableStateOf<IosPlaylist?>(null) }
    var deletePlaylist by remember { mutableStateOf<IosPlaylist?>(null) }
    var folderActions by remember { mutableStateOf<LibraryFolder?>(null) }
    var renameFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var addTrack by remember { mutableStateOf<String?>(null) }
    // Android opens FileDetailsActivity; here the shared screen covers the window.
    var detailsFor by remember { mutableStateOf<String?>(null) }
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
    fun folderName(key: String): String = if (library.isDemo(key)) demoName
        else library.parent(key)?.let(library::label).orEmpty()
    fun title(key: String) = midiDisplayTitle(library.fileName(key), state.metadata[key]?.title)

    // Folders and tracks of [dir] are read together and shown once they belong to the open folder.
    val listing by produceState<IosLibrary.Listing?>(null, dir, state.libraryRevision) {
        value = withContext(Dispatchers.Default) { runCatching { library.list(dir) }.getOrNull() ?: IosLibrary.Listing(dir, emptyList(), emptyList()) }
    }
    val openListing = listing?.takeIf { it.dir == dir }
    val atHome = dir == library.home
    // Linked folders sit at the start folder, like the volumes Android offers there.
    val childFolders = remember(openListing, atHome, state.libraryRevision) {
        val linked = if (atHome) library.linkedRoots().map { LibraryFolder("${it.id}:", it.label) } else emptyList()
        linked + openListing?.folders.orEmpty().map { LibraryFolder(it, library.label(it)) }
    }
    val searching = search && query.isNotBlank()
    val searchFiles by produceState<List<String>?>(null, dir, search, state.libraryRevision) {
        value = null
        if (!search) return@produceState
        // Search walks the open folder and everything below it, like Android and desktop.
        val found = withContext(Dispatchers.Default) {
            var latest = emptyList<String>()
            var shown = -1
            library.walk(dir, stop = { !isActive }) { batch ->
                latest = batch
                // Batch UI updates while a large folder tree is read.
                if (batch.size - shown >= 50) { value = batch; shown = batch.size }
            }
            latest
        }
        value = found
    }
    val browsingFolder = !playlistsSelected && !searching
    val paths = remember(openListing, searchFiles, state.metadata, playlist, playlistsSelected, searching, query, playlistEditOrder, editing) {
        when {
            playlistsSelected -> if (editing) playlistEditOrder ?: playlist?.tracks.orEmpty() else playlist?.tracks.orEmpty()
            searching -> searchFiles.orEmpty()
            else -> openListing?.tracks.orEmpty()
        }.filter { key ->
            !searching || matchesMidiSearch(library.fileName(key), state.metadata[key]?.title,
                state.metadata[key]?.copyright, folderName(key), query)
        }
    }
    LaunchedEffect(paths) { controller.loadMetadata(paths.take(500)) }
    LaunchedEffect(searchFiles) { searchFiles?.let { controller.loadMetadata(it.take(2000)) } }
    LaunchedEffect(demos) { if (demos) controller.loadDemos() }
    val tracks = remember(paths, state.metadata) {
        paths.map { key ->
            val metadata = state.metadata[key]
            LibraryTrack(key, title(key),
                midiDisplaySecondaryText(library.fileName(key), folderName(key), metadata?.title, metadata?.copyright),
                metadata?.durationMs ?: 0, controller.trackId(key), metadata?.loopPointMs)
        }
    }
    val sourceTitle = when {
        playlistsSelected -> playlist?.name
        else -> library.label(dir).takeIf { !demos } ?: demoName
    }
    val listState = remember(dir, playlistsSelected, playlistId) { LazyListState() }
    val currentArtwork = rememberArtwork(library, state.current?.let(library::parent))

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

    fun closePlaylist() {
        playlistId = null
        editing = false
        playlistEditOrder = null
    }

    Box(Modifier.fillMaxSize()) {
        ResponsivePlayerLayout(
            showSettings = showSettings,
            // The compact player is the draggable sheet inside the library, as on Android.
            showPlayer = false,
            onBack = { wide -> if (showSettings) showSettings = false else if (!wide) showNowPlaying = false },
            settings = {
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
            },
            player = { wide -> playerContent(wide) },
            library = { wide ->
                Box(Modifier.fillMaxSize().playerSheetHost(playerSheet)) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbar) },
                        topBar = {
                            LibraryTopAppBar(
                                openPlaylistName = playlist?.name,
                                onClosePlaylist = { closePlaylist() },
                                editingName = playlistNameDraft.takeIf { editing && playlist != null },
                                onEditingNameChange = { playlistNameDraft = it },
                                actions = {
                                    if (!playlistsSelected) {
                                        IconButton(onClick = { IosPlatformUi.pickMidiFiles(controller::openFiles) }) {
                                            Icon(Icons.Default.AudioFile, playerString("ios_open_files"))
                                        }
                                        IconButton(onClick = { IosPlatformUi.pickFolder(controller::addLinkedFolder) }) {
                                            Icon(Icons.Default.CreateNewFolder, playerString("ios_add_folder"))
                                        }
                                        IconButton(onClick = { search = !search; query = "" }) {
                                            Surface(shape = RoundedCornerShape(50),
                                                color = if (search) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                                                Icon(Icons.Default.Search, playerString("search"), modifier = Modifier.padding(8.dp))
                                            }
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
                                    }) { Icon(if (editing) Icons.Default.Done else Icons.Default.Edit, playerString(if (editing) "action_done" else "action_edit")) }
                                    if (!editing) IconButton(onClick = { showSettings = true }) {
                                        Icon(Icons.Default.Settings, playerString("settings"))
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
                                key(dir, playlistsSelected, playlistId) {
                                    when {
                                        playlistsSelected && playlist == null -> PlaylistScreen(
                                            state.playlists.map { LibraryPlaylist(it.id, it.name, it.tracks.size) },
                                            onCreatePlaylist = { createPlaylist = true },
                                            onOpenPlaylist = { playlistId = it.id },
                                            onShowPlaylistActions = { item -> playlistActions = state.playlists.first { it.id == item.id } },
                                            onPlayPlaylist = { item -> controller.playPlaylist(item.id) })
                                        else -> {
                                            val list: @Composable (PaddingValues) -> Unit = { headerPadding ->
                                                TrackList(tracks, contentPadding = headerPadding,
                                                    listContext = if (playlistsSelected) MidiListContext.Playlist else if (searching) MidiListContext.Search else MidiListContext.Browse,
                                                    isLoading = state.busy || (search && searchFiles == null),
                                                    isEditMode = editing && playlistsSelected, selectedUri = state.current,
                                                    listState = listState,
                                                    onMoveItem = { id, delta ->
                                                        val order = (playlistEditOrder ?: playlist?.tracks.orEmpty()).toMutableList()
                                                        val from = order.indexOfFirst { controller.trackId(it) == id }
                                                        if (from >= 0) {
                                                            order.add((from + delta).coerceIn(0, order.lastIndex), order.removeAt(from))
                                                            playlistEditOrder = order
                                                        }
                                                    },
                                                    onRemoveItem = { id ->
                                                        playlistEditOrder = (playlistEditOrder ?: playlist?.tracks.orEmpty()).filterNot { controller.trackId(it) == id }
                                                    },
                                                    onItemClick = { track ->
                                                        showSettings = false
                                                        controller.select(track.uri, paths, sourceTitle, playlist?.id)
                                                    },
                                                    onAddToPlaylist = { addTrack = it.uri },
                                                    onQueueNext = { controller.queueNext(it.uri, paths, sourceTitle) },
                                                    actions = { track, close ->
                                                        TrackActions(controller, track.uri, title(track.uri), showPlay = true, close = close,
                                                            onAddToPlaylist = { addTrack = track.uri },
                                                            onDetails = { detailsFor = track.uri },
                                                            onQueueNext = { controller.queueNext(track.uri, paths, sourceTitle) })
                                                    },
                                                    hasDirectoryEntries = browsingFolder && (childFolders.isNotEmpty() || atHome),
                                                    directoryContent = {
                                                        if (browsingFolder) directoryEntries(childFolders, viewMode,
                                                            onFolder = { controller.browse(it.key) },
                                                            demo = if (atHome) ({ controller.browse("${IosLibrary.DEMO}:") }) else null,
                                                            cover = { folder -> rememberArtwork(library, folder.key) },
                                                            onFolderActions = { folder -> folderActions = folder })
                                                    })
                                            }
                                            if (browsingFolder) FolderBrowseHeader(folderBrowseSummary(childFolders.size, tracks.size), viewMode,
                                                onViewModeChange = { controller.setFolderViewMode(it == FolderViewMode.List) }) { padding ->
                                                if (openListing != null) {
                                                    if (atHome && childFolders.isEmpty() && tracks.isEmpty()) EmptyLibraryHint(padding)
                                                    list(padding)
                                                }
                                            } else list(PaddingValues())
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
        detailsFor?.let { key ->
            val details by produceState<FileDetails?>(null, key) {
                value = withContext(Dispatchers.Default) { controller.fileDetails(key) }
            }
            Surface(Modifier.fillMaxSize().zIndex(10f)) {
                FileDetailsScreen(details = details, onBack = { detailsFor = null })
            }
        }
    }

    if (nowPlayingActions) state.current?.let { current ->
        TrackActions(controller, current, title(current), showPlay = false, close = { nowPlayingActions = false },
            onAddToPlaylist = { addTrack = current }, onDetails = { detailsFor = current }, onQueueNext = {})
    }
    folderActions?.let { folder ->
        val linkedRoot = library.isLinkedRoot(folder.key)
        FolderActionsDialog(folder.name, library.resolve(folder.key).orEmpty(), { folderActions = null },
            onOpen = {
                folderActions = null
                library.resolve(folder.key)?.let(IosPlatformUi::openInFiles)
            },
            onRename = { folderActions = null; renameFolder = folder },
            onRemove = if (linkedRoot) ({ folderActions = null; controller.removeLinkedFolder(folder.key) }) else null)
    }
    renameFolder?.let { folder ->
        RenameFolderDialog(folder.name, { renameFolder = null }) { name ->
            controller.renameFolder(folder.key, name)
            renameFolder = null
        }
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

/** The song actions of Android's MidiFileActionsDialog (details and loop editing follow later). */
@Composable
private fun TrackActions(
    controller: IosController, key: String, title: String, showPlay: Boolean, close: () -> Unit,
    onAddToPlaylist: () -> Unit, onDetails: () -> Unit, onQueueNext: () -> Unit,
) {
    MidiFileActionsDialog(title = title, onDismiss = close, showPlayAction = showPlay,
        loopEditEnabled = !controller.library.isDemo(key),
        onPlay = { close(); onQueueNext() },
        onShare = { close(); controller.library.resolve(key)?.let(IosPlatformUi::share) },
        onDetails = { close(); onDetails() },
        onAddToPlaylist = { close(); onAddToPlaylist() })
}

@Composable
private fun EmptyLibraryHint(padding: PaddingValues) {
    Text(playerString("ios_files_hint"), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(padding).padding(horizontal = 32.dp, vertical = 96.dp).zIndex(1f))
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
