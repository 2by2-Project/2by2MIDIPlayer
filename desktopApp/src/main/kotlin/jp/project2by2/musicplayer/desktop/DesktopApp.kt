package jp.project2by2.musicplayer.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import jp.project2by2.musicplayer.MiniPlayerContent
import jp.project2by2.musicplayer.PianoRollData
import jp.project2by2.musicplayer.midiDisplayTitle
import jp.project2by2.musicplayer.midiDisplaySecondaryText
import jp.project2by2.musicplayer.matchesMidiSearch
import jp.project2by2.musicplayer.model.*
import jp.project2by2.musicplayer.state.NowPlayingState
import jp.project2by2.musicplayer.ui.browse.*
import jp.project2by2.musicplayer.ui.playlist.*
import jp.project2by2.musicplayer.ui.player.*
import jp.project2by2.musicplayer.ui.settings.SettingsScreen
import kotlinx.coroutines.isActive
import java.io.File

/** Navigation and platform callbacks only; Android-derived screen presentation is in commonMain. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopApp(controller: DesktopController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var recommendedFonts by remember { mutableStateOf(false) }
    var checkedInitialFont by remember { mutableStateOf(false) }
    val windowsFont = remember { windowsSoundFont() }
    LaunchedEffect(state.audioReady, state.soundFont) {
        if (state.audioReady && !checkedInitialFont) {
            checkedInitialFont = true
            if (state.soundFont == null) recommendedFonts = true
        }
    }
    jp.project2by2.musicplayer.ui.settings.SoundFontLoadingDialog(state.soundFontLoading && !recommendedFonts)
    if (recommendedFonts) jp.project2by2.musicplayer.ui.settings.RecommendedSoundFontDialog(
        onDismiss = { recommendedFonts = false },
        isDownloading = state.soundFontLoading,
        downloadProgress = state.soundFontDownloadProgress,
        onDownload = { option -> controller.downloadFont(option) { success -> if (success) recommendedFonts = false } },
        onUseWindowsSoundFont = windowsFont?.let { file -> {
            recommendedFonts = false
            controller.setFont(file)
        } },
    )
    val artworkLoader = remember { DesktopArtwork() }
    val currentArtwork = rememberDesktopArtwork(artworkLoader,
        state.current?.let { controller.midiFiles.resolve(it).parentFile })
    var playlistsSelected by remember { mutableStateOf(false) }
    var playlistId by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(FolderViewMode.List) }
    var settings by remember { mutableStateOf(false) }
    var nowPlaying by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    // Entry marked with the arrow keys, valid only for the folder/search it was made in.
    var highlight by remember { mutableStateOf<KeyHighlight?>(null) }
    // True while the library is composed: in a narrow window the player can cover it.
    var libraryShown by remember { mutableStateOf(false) }
    var demos by remember { mutableStateOf(false) }
    var nowPlayingActions by remember { mutableStateOf(false) }
    var shareTrack by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var createPlaylist by remember { mutableStateOf(false) }
    var playlistActions by remember { mutableStateOf<DesktopPlaylist?>(null) }
    var renamePlaylist by remember { mutableStateOf<DesktopPlaylist?>(null) }
    var deletePlaylist by remember { mutableStateOf<DesktopPlaylist?>(null) }
    var folderActions by remember { mutableStateOf<LibraryFolder?>(null) }
    var renameFolder by remember { mutableStateOf<LibraryFolder?>(null) }
    var addTrack by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var playlistNameDraft by remember { mutableStateOf("") }
    val playlist = state.playlists.find { it.id == playlistId }
    // Editing starts from the toolbar or the playlist menu; either way the name field starts from the current name.
    LaunchedEffect(editing, playlistId) { if (editing) playlistNameDraft = playlist?.name.orEmpty() }
    val demoFolderName = playerString("folder_demo_name")
    fun folderName(path: String) = if (DesktopMidiFiles.isDemo(path)) demoFolderName else File(path).parentFile?.name.orEmpty()
    fun title(path: String) = midiDisplayTitle(DesktopMidiFiles.fileName(path), state.metadata[path]?.title)
    // Browse is a file explorer, starting at home and remembering the last folder.
    val index = controller.folderIndex
    val indexState by index.state.collectAsState()
    val dir = state.browseDir ?: index.home.path
    val drivesName = playerString("browse_drives")
    fun folderLabel(path: String) = when {
        path == DesktopFolderIndex.DRIVES -> drivesName
        File(path).parentFile == null -> path.trimEnd('\\').ifEmpty { path }
        else -> File(path).name
    }
    // Folders and tracks of [dir] are read together. The list is shown only once they belong to the
    // open folder: rows arriving after it is laid out would be inserted above the visible ones, and the
    // list keeps those in view, leaving a new folder scrolled to its end.
    val listing by produceState<DirectoryListing?>(null, dir, indexState.revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            DirectoryListing(dir, index.children(dir).map { LibraryFolder(it.path, folderLabel(it.path)) }, index.tracks(dir))
        }
    }
    val openListing = listing?.takeIf { it.dir == dir }
    val childFolders = openListing?.folders.orEmpty()
    val directoryTracks = openListing?.tracks.orEmpty()
    val searching = search && query.isNotBlank()
    // Search walks the open folder on disk (started when the search field opens), so it works in any
    // folder, not only where the home index reaches. The drive list has no folder to walk and uses the index.
    val searchFiles by produceState<List<String>?>(null, dir, search, indexState.revision) {
        value = null
        if (!search || dir == DesktopFolderIndex.DRIVES) return@produceState
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            index.searchUnder(dir, stop = { !isActive }) { found -> value = found }
        }
    }
    val browsingFolder = !playlistsSelected && !demos && !searching
    val paths = remember(directoryTracks, indexState, searchFiles, state.demoFiles, state.metadata, playlist, playlistsSelected, dir, demos, searching, query) {
        when {
            playlistsSelected -> playlist?.paths.orEmpty()
            demos -> state.demoFiles
            // Search covers the open folder and everything below it, like Android.
            searching -> searchFiles ?: index.filesUnder(dir, indexState)
            else -> directoryTracks
        }.filter { path ->
            !searching || matchesMidiSearch(DesktopMidiFiles.fileName(path), state.metadata[path]?.title,
                state.metadata[path]?.copyright, folderName(path), query)
        }
    }
    LaunchedEffect(paths) { controller.loadMetadata(paths.take(500)) }
    val tracks = remember(paths, state.metadata) {
        paths.map { path -> LibraryTrack(path, title(path),
            midiDisplaySecondaryText(DesktopMidiFiles.fileName(path), folderName(path), state.metadata[path]?.title, state.metadata[path]?.copyright),
            state.metadata[path]?.durationMs ?: 0, controller.trackId(path), state.metadata[path]?.loopPointMs) }
    }
    val showDemo = browsingFolder && dir == index.home.path
    val gridFolders = browsingFolder && viewMode == FolderViewMode.Grid
    val folderKeys = if (browsingFolder) childFolders.map { it.key } else emptyList()
    val entryKeys = remember(folderKeys, showDemo, paths) { browseEntryKeys(folderKeys, showDemo, paths) }
    val activeHighlight = highlight?.takeIf { it.scope == highlightScope(dir, demos, searching) && it.key in entryKeys }?.key
    // A folder opens at the top (fresh state), and the highlighted entry is scrolled into view.
    val listState = remember(dir, demos, playlistsSelected, playlistId) { LazyListState() }
    LaunchedEffect(activeHighlight, listState) {
        activeHighlight?.let { listState.ensureVisible(browseListIndex(entryKeys, folderKeys.size, gridFolders, it)) }
    }
    // Text dialogs and modal dialogs keep the keyboard to themselves.
    val dialogOpen = recommendedFonts || state.soundFontLoading || state.error != null || createPlaylist || renameFolder != null ||
        renamePlaylist != null || deletePlaylist != null || folderActions != null || playlistActions != null ||
        addTrack != null || nowPlayingActions || shareTrack != null
    // Callbacks may outlive the composition that created them (the compiler memoizes function
    // references), so they read the open folder from the controller instead of capturing [dir].
    fun backLibrary() {
        when {
            search -> { search = false; query = "" }
            playlistId != null -> { playlistId = null; editing = false }
            demos -> demos = false
            else -> index.parent(controller.state.value.browseDir ?: index.home.path)?.let(controller::browse)
        }
    }
    fun back(wide: Boolean) {
        when {
            settings -> settings = false
            nowPlaying && !wide -> nowPlaying = false
            else -> backLibrary()
        }
    }
    // Shortcuts: Ctrl+F toggles search; Right/Left skip measures; Up/Down/Enter/Backspace
    // drive the library like a file explorer. The handler is installed once, so it reads the controller
    // state directly where it can.
    fun handleShortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val primary = event.isCtrlPressed || event.isMetaPressed
        val plain = !primary && !event.isAltPressed && !event.isShiftPressed
        val blocked = settings || editing || dialogOpen
        val browsable = !blocked && libraryShown && !playlistsSelected
        when {
            primary && event.key == Key.F -> {
                if (playlistsSelected) return false
                // Like the toolbar button: opens the search field, or closes it again.
                search = !search; query = ""
                return true
            }
            plain && (event.key == Key.DirectionRight || event.key == Key.DirectionLeft) -> {
                // Text being typed keeps its arrow keys for the cursor.
                if (blocked || searchFocused) return false
                val current = controller.state.value
                val measures = current.index?.measurePositionsMs?.takeIf { current.current != null } ?: return false
                val target = measureSkipTarget(measures, current.audio.positionMs, if (event.key == Key.DirectionRight) 1 else -1)
                if (target != null) controller.seek(target)
                return true
            }
            plain && (event.key == Key.DirectionDown || event.key == Key.DirectionUp) -> {
                // Also works from the search field: its single line has no use for Up/Down.
                if (!browsable) return false
                val next = moveBrowseHighlight(entryKeys, folderKeys.size, gridFolders, activeHighlight, event.key == Key.DirectionDown) ?: return false
                highlight = KeyHighlight(highlightScope(dir, demos, searching), next)
                return true
            }
            plain && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                if (!browsable) return false
                val key = activeHighlight ?: return false
                when {
                    key == DEMO_ENTRY_KEY -> { demos = true; controller.loadDemos() }
                    key in folderKeys -> controller.browse(key)
                    else -> { settings = false; controller.select(key, paths) }
                }
                return true
            }
            plain && event.key == Key.Backspace -> {
                // Never while searching: Backspace edits the query there.
                if (!browsable || search || searchFocused) return false
                if (demos) { demos = false; return true }
                val current = controller.state.value.browseDir ?: index.home.path
                val parent = index.parent(current) ?: return true
                // Like Explorer, the folder just left is marked in its parent.
                highlight = KeyHighlight(highlightScope(parent, false, false), current)
                controller.browse(parent)
                return true
            }
        }
        return false
    }
    val shortcut by rememberUpdatedState<(KeyEvent) -> Boolean>({ handleShortcut(it) })
    // Key events only reach this window while something holds focus, so the root takes it at start and
    // back whenever focus is lost (a closed search field or dialog leaves nothing focused).
    val rootFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var rootHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(rootHasFocus, search, createPlaylist, renameFolder, renamePlaylist, addTrack, folderActions, playlistActions, nowPlayingActions) {
        if (!rootHasFocus && !searchFocused) runCatching { rootFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { shortcut(it) }
        .focusRequester(rootFocus).onFocusChanged { rootHasFocus = it.hasFocus }.focusable()
        .desktopFileDropTarget(controller)) {
        ResponsivePlayerLayout(
            showSettings = settings,
            showPlayer = nowPlaying && state.current != null,
            onBack = { wide -> back(wide) },
            settings = {
            SettingsScreen(
                soundFontName = state.soundFont?.let { File(it).name }, hasSoundFont = state.soundFont != null,
                soundFontLoading = state.soundFontLoading,
                onConfigureFileAssociations = {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DesktopFileAssociations().configure() }
                },
                maxVoices = state.maxVoices, effectsEnabled = state.effectsEnabled, reverbStrength = state.reverbStrength,
                loopEnabled = state.loop, shuffleEnabled = state.shuffle, onBack = { settings = false },
                onPickSoundFont = { chooseFiles(scope, soundFont = true, result = { it.firstOrNull()?.let(controller::setFont) }, onError = controller::reportError) },
                onRecommendedSoundFonts = { recommendedFonts = true },
                onMaxVoicesChange = controller::setMaxVoices, onEffectsChange = controller::setEffectsEnabled,
                onReverbChange = controller::setReverbStrength,
                onLoopChange = { if (it != state.loop) controller.toggleLoop() },
                onShuffleChange = { if (it != state.shuffle) controller.toggleShuffle() })
            },
            library = { wide ->
            DisposableEffect(Unit) { libraryShown = true; onDispose { libraryShown = false } }
            Scaffold(topBar = {
                // The logo stays put; where you are is shown by the address bar below.
                LibraryTopAppBar(
                    openPlaylistName = playlist?.name,
                    onClosePlaylist = { playlistId = null; editing = false },
                    editingName = playlistNameDraft.takeIf { editing && playlist != null },
                    onEditingNameChange = { playlistNameDraft = it },
                    actions = {
                        if (!playlistsSelected) {
                            IconButton(onClick = { chooseFiles(scope, result = controller::openFiles, onError = controller::reportError) }) { Icon(Icons.Default.AudioFile, "MIDIファイルを開いて再生") }
                        }
                        if (playlist != null) IconButton(onClick = {
                            // Finishing an edit renames the playlist when the name was changed.
                            val name = playlistNameDraft.trim()
                            if (editing && name.isNotEmpty() && name != playlist.name) controller.renamePlaylist(playlist.id, name)
                            editing = !editing
                        }) { Icon(if (editing) Icons.Default.Done else Icons.Default.Edit, "編集") }
                        if (!playlistsSelected) IconButton(onClick = { search = !search; query = "" }) {
                            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                color = if (search) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent) {
                                Icon(Icons.Default.Search, playerString("search"), modifier = Modifier.padding(8.dp))
                            }
                        }
                        IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, playerString("settings")) }
                    })
            }, bottomBar = {
                Column {
                    if (!wide) state.current?.let { path ->
                        MiniPlayerContent(title(path), state.metadata[path]?.copyright ?: folderName(path), currentArtwork, state.audio.playing,
                            if (state.audio.durationMs > 0) state.audio.positionMs.toFloat() / state.audio.durationMs else 0f,
                            formatPlayerDuration(state.audio.positionMs), controller::togglePlay, { settings = false; nowPlaying = true })
                    }
                    PlayerNavigationBar(playlistsSelected,
                        onBrowse = { playlistsSelected = false; playlistId = null; search = false; query = ""; editing = false; demos = false },
                        onPlaylists = { playlistsSelected = true; playlistId = null; search = false; query = ""; editing = false; demos = false })
                }
            }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    if (!playlistsSelected) LibraryAddressBar(index.ancestry(dir).map { AddressSegment(it, folderLabel(it)) },
                        demoOpen = demos,
                        // Callbacks may outlive their composition; they must not capture [dir].
                        onOpenFolder = { demos = false; controller.browse(it) })
                    Box(Modifier.onFocusChanged { searchFocused = it.hasFocus }) {
                        BrowseSearchField(visible = search, query = query, onQueryChange = { query = it })
                    }
                    // A new location gets fresh list state, so every folder or playlist opens at the top.
                    Box(Modifier.weight(1f).fillMaxWidth()) { key(dir, demos, playlistsSelected, playlistId) {
                    when {
                        playlistsSelected && playlist == null -> PlaylistScreen(
                            state.playlists.map { LibraryPlaylist(it.id, it.name, it.paths.size) },
                            onCreatePlaylist = { createPlaylist = true }, onOpenPlaylist = { playlistId = it.id },
                            onShowPlaylistActions = { item -> playlistActions = state.playlists.first { it.id == item.id } },
                            onPlayPlaylist = { item -> settings = false; state.playlists.first { it.id == item.id }.paths.let { p -> p.firstOrNull()?.let { controller.select(it, p) } } })
                        else -> {
                        val list: @Composable (PaddingValues) -> Unit = { headerPadding -> TrackList(tracks, contentPadding = headerPadding,
                            listContext = if (playlistsSelected) MidiListContext.Playlist else if (search) MidiListContext.Search else MidiListContext.Browse,
                            isLoading = state.busy,
                            isEditMode = editing && !search, selectedUri = state.current,
                            highlightedUri = activeHighlight, listState = listState,
                            onMoveItem = { id, delta -> playlist?.let { controller.movePlaylistTrack(it.id, tracks.first { t -> t.playlistItemId == id }.uri, delta) } },
                            onRemoveItem = { id -> playlist?.let { controller.removeFromPlaylist(tracks.first { t -> t.playlistItemId == id }.uri, it.id) } },
                            onItemClick = { settings = false; controller.select(it.uri, paths) }, onAddToPlaylist = { addTrack = it.uri },
                            onQueueNext = { controller.queueNext(it.uri) },
                            actions = { track, close ->
                                MidiFileActionsDialog(title = track.title, onDismiss = close,
                                    onPlay = { close(); controller.queueNext(track.uri) },
                                    onShare = { close(); shareTrack = track.uri },
                                    onAddToPlaylist = { close(); addTrack = track.uri })
                            },
                            hasDirectoryEntries = browsingFolder && (childFolders.isNotEmpty() || showDemo),
                            directoryContent = {
                                if (browsingFolder) directoryEntries(childFolders, viewMode,
                                    onFolder = { controller.browse(it.key) },
                                    demo = if (showDemo) ({ demos = true; controller.loadDemos() }) else null,
                                    cover = { folder -> rememberDesktopArtwork(artworkLoader, File(folder.key)) },
                                    // Drive roots cannot be renamed; other folders offer open and rename.
                                    onFolderActions = { folder -> if (File(folder.key).parentFile != null) folderActions = folder },
                                    highlightKey = activeHighlight)
                            }) }
                        if (browsingFolder) FolderBrowseHeader(folderBrowseSummary(childFolders.size, tracks.size), viewMode,
                            onViewModeChange = { viewMode = it }) { if (openListing != null) list(it) } else list(PaddingValues())
                        }
                    }
                    } }
                }
            }
            },
            player = { wide ->
                if (state.current == null) {
                    EmptyNowPlayingPane()
                } else {
            val data = remember(state.index) { state.index?.let { PianoRollData(it.notes, it.totalDurationMs, it.measurePositionsMs, it.measureTickPositions, it.totalTicks, it.tickTimeAnchors) } }
            val position = NowPlayingState(state.audio.playing, state.audio.positionMs, state.audio.durationMs,
                state.metadata[state.current]?.loopPointMs ?: 0L, state.audio.durationMs)
            NowPlayingSheet(title(state.current!!), state.metadata[state.current]?.copyright ?: folderName(state.current!!),
                coverBitmap = currentArtwork,
                ui = position, pianoRollData = data, loopEnabled = state.loop, shuffleEnabled = state.shuffle,
                onLoopChange = { controller.toggleLoop() }, onShuffleChange = { controller.toggleShuffle() },
                onSeekToMs = controller::seek, onPrevious = { controller.next(-1) }, onNext = { controller.next(1) },
                onPlayPause = controller::togglePlay, trackKey = state.current!!, onClose = if (wide) null else ({ nowPlaying = false }),
                showActions = true, onActionsClick = { nowPlayingActions = true })
                }
            }
        )
        state.error?.let { error -> AlertDialog(onDismissRequest = controller::dismissError, title = { Text("エラー") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = controller::dismissError) { Text("OK") } }) }
    }
    if (nowPlayingActions) state.current?.let { path ->
        MidiFileActionsDialog(title = title(path), onDismiss = { nowPlayingActions = false }, showPlayAction = false,
            onPlay = {}, onShare = { nowPlayingActions = false; shareTrack = path },
            onAddToPlaylist = { nowPlayingActions = false; addTrack = path })
    }
    shareTrack?.let { path ->
        DesktopShareDialog(file = controller.midiFiles.resolve(path), onDismiss = { shareTrack = null })
    }
    folderActions?.let { folder ->
        FolderActionsDialog(folder.name, folder.key, { folderActions = null },
            onOpen = { folderActions = null; controller.openFolder(folder.key) },
            onRename = { folderActions = null; renameFolder = folder })
    }
    renameFolder?.let { folder ->
        RenameFolderDialog(folder.name, { renameFolder = null }) { name ->
            controller.renameFolder(folder.key, name)
            renameFolder = null
        }
    }
    if (createPlaylist) CreatePlaylistDialog({ createPlaylist = false }, { controller.createPlaylist(it); createPlaylist = false })
    playlistActions?.let { selected -> PlaylistActionsDialog(selected.name, { playlistActions = null },
        { playlistId = selected.id; editing = true; playlistActions = null },
        { renamePlaylist = selected; playlistActions = null }, { deletePlaylist = selected; playlistActions = null }) }
    renamePlaylist?.let { selected -> RenamePlaylistDialog(selected.name, { renamePlaylist = null }, { controller.renamePlaylist(selected.id, it); renamePlaylist = null }) }
    deletePlaylist?.let { selected -> AlertDialog(onDismissRequest = { deletePlaylist = null }, title = { Text("プレイリストを削除") },
        text = { Text(selected.name) }, confirmButton = { TextButton(onClick = { controller.deletePlaylist(selected.id); deletePlaylist = null; playlistId = null }) { Text("削除") } },
        dismissButton = { TextButton(onClick = { deletePlaylist = null }) { Text("キャンセル") } }) }
    addTrack?.let { path -> AlertDialog(onDismissRequest = { addTrack = null }, title = { Text(File(path).name) },
        text = { Column {
            TextButton(onClick = { createPlaylist = true }) { Text("プレイリストを作成") }
            state.playlists.forEach { list -> TextButton(onClick = { controller.addToPlaylist(path, list.id); addTrack = null }) { Text(list.name) } }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { addTrack = null }) { Text("キャンセル") } }) }
}

/** Entry marked with the keyboard, tied to the [scope] (folder and mode) it was made in. */
private data class KeyHighlight(val scope: String, val key: String)

private fun highlightScope(dir: String, demos: Boolean, searching: Boolean) = "$dir|$demos|$searching"

private data class DirectoryListing(val dir: String, val folders: List<LibraryFolder>, val tracks: List<String>)
