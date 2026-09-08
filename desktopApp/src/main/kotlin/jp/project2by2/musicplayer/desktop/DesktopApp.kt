package jp.project2by2.musicplayer.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import java.io.File

/** Navigation and platform callbacks only; Android-derived screen presentation is in commonMain. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopApp(controller: DesktopController) {
    val state by controller.state.collectAsState()
    val artworkLoader = remember { DesktopArtwork() }
    val currentArtwork = rememberDesktopArtwork(artworkLoader,
        state.current?.let { controller.midiFiles.resolve(it).parentFile })
    var playlistsSelected by remember { mutableStateOf(false) }
    var folderKey by remember { mutableStateOf<String?>(null) }
    var playlistId by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(FolderViewMode.Grid) }
    var settings by remember { mutableStateOf(false) }
    var nowPlaying by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    var demos by remember { mutableStateOf(false) }
    var nowPlayingActions by remember { mutableStateOf(false) }
    var shareTrack by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var createPlaylist by remember { mutableStateOf(false) }
    var playlistActions by remember { mutableStateOf<DesktopPlaylist?>(null) }
    var renamePlaylist by remember { mutableStateOf<DesktopPlaylist?>(null) }
    var deletePlaylist by remember { mutableStateOf<DesktopPlaylist?>(null) }
    var addTrack by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    val playlist = state.playlists.find { it.id == playlistId }
    val demoFolderName = playerString("folder_demo_name")
    fun folderName(path: String) = if (DesktopMidiFiles.isDemo(path)) demoFolderName else File(path).parentFile?.name.orEmpty()
    fun title(path: String) = midiDisplayTitle(DesktopMidiFiles.fileName(path), state.metadata[path]?.title)
    val folders = remember(state.files) {
        state.files.map { File(it).parentFile }.distinctBy { it.path }
            .map { LibraryFolder(it.path, it.name) }.sortedBy { it.name.lowercase() }
    }
    val paths = remember(state.files, state.demoFiles, state.metadata, playlist, playlistsSelected, folderKey, demos, search, query) {
        (if (playlistsSelected) playlist?.paths.orEmpty() else if (demos) state.demoFiles else state.files).filter { path ->
            (playlistsSelected || demos || folderKey == null || File(path).parent == folderKey) &&
                (!search || matchesMidiSearch(DesktopMidiFiles.fileName(path), state.metadata[path]?.title,
                    state.metadata[path]?.copyright, folderName(path), query))
        }
    }
    val tracks = remember(paths, state.metadata) {
        paths.map { path -> LibraryTrack(path, title(path),
            midiDisplaySecondaryText(DesktopMidiFiles.fileName(path), folderName(path), state.metadata[path]?.title, state.metadata[path]?.copyright),
            state.metadata[path]?.durationMs ?: 0, controller.trackId(path), state.metadata[path]?.loopPointMs) }
    }
    fun backLibrary() {
        when {
            search -> { search = false; query = "" }
            playlistId != null -> { playlistId = null; editing = false }
            demos -> demos = false
            else -> folderKey = null
        }
    }
    fun back(wide: Boolean) {
        when {
            settings -> settings = false
            nowPlaying && !wide -> nowPlaying = false
            else -> backLibrary()
        }
    }
    Box(Modifier.fillMaxSize()) {
        ResponsivePlayerLayout(
            showSettings = settings,
            showPlayer = nowPlaying && state.current != null,
            onBack = { wide -> back(wide) },
            settings = {
            SettingsScreen(
                soundFontName = state.soundFont?.let { File(it).name }, hasSoundFont = state.soundFont != null,
                maxVoices = state.maxVoices, effectsEnabled = state.effectsEnabled, reverbStrength = state.reverbStrength,
                loopEnabled = state.loop, shuffleEnabled = state.shuffle, onBack = { settings = false },
                onPickSoundFont = { chooseFiles(soundFont = true) { it.firstOrNull()?.let(controller::setFont) } },
                onMaxVoicesChange = controller::setMaxVoices, onEffectsChange = controller::setEffectsEnabled,
                onReverbChange = controller::setReverbStrength,
                onLoopChange = { if (it != state.loop) controller.toggleLoop() },
                onShuffleChange = { if (it != state.shuffle) controller.toggleShuffle() })
            },
            library = { wide ->
            Scaffold(topBar = {
                PlayerTopAppBar(
                    title = {
                        val screenTitle = playlist?.name ?: if (demos) demoFolderName else folderKey?.let { File(it).name }
                        if (screenTitle == null) PlayerLogo() else Text(screenTitle, maxLines = 1,
                            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    },
                    navigationIcon = {
                        if (folderKey != null || playlistId != null || demos) IconButton(onClick = ::backLibrary) {
                            Icon(Icons.Default.ArrowBack, playerString("back"))
                        }
                    },
                    actions = {
                        if (!playlistsSelected) {
                            IconButton(onClick = { chooseFiles(directory = true, result = controller::importFiles) }) { Icon(Icons.Default.CreateNewFolder, "フォルダを追加") }
                            IconButton(onClick = { chooseFiles(result = controller::importFiles) }) { Icon(Icons.Default.NoteAdd, "MIDIファイルを追加") }
                        }
                        if (playlist != null) IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Default.Done else Icons.Default.Edit, "編集") }
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
                        onBrowse = { playlistsSelected = false; folderKey = null; playlistId = null; search = false; query = ""; editing = false; demos = false },
                        onPlaylists = { playlistsSelected = true; folderKey = null; playlistId = null; search = false; query = ""; editing = false; demos = false })
                }
            }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    BrowseSearchField(visible = search, query = query, onQueryChange = { query = it })
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        playlistsSelected && playlist == null -> PlaylistScreen(
                            state.playlists.map { LibraryPlaylist(it.id, it.name, it.paths.size) },
                            onCreatePlaylist = { createPlaylist = true }, onOpenPlaylist = { playlistId = it.id },
                            onShowPlaylistActions = { item -> playlistActions = state.playlists.first { it.id == item.id } },
                            onPlayPlaylist = { item -> settings = false; state.playlists.first { it.id == item.id }.paths.let { p -> p.firstOrNull()?.let { controller.select(it, p) } } })
                        !playlistsSelected && folderKey == null && !demos && (!search || query.isBlank()) -> BrowseScreen(
                            folders, cover = { folder -> rememberDesktopArtwork(artworkLoader, File(folder.key)) },
                            onFolderClick = { folderKey = it.key }, onDemoMusicClick = { demos = true; controller.loadDemos() },
                            viewMode = viewMode, onViewModeChange = { viewMode = it })
                        else -> TrackList(tracks,
                            listContext = if (playlistsSelected) MidiListContext.Playlist else if (search) MidiListContext.Search else MidiListContext.Browse,
                            isLoading = state.busy, isEditMode = editing && !search, selectedUri = state.current,
                            onMoveItem = { id, delta -> playlist?.let { controller.movePlaylistTrack(it.id, tracks.first { t -> t.playlistItemId == id }.uri, delta) } },
                            onRemoveItem = { id -> playlist?.let { controller.removeFromPlaylist(tracks.first { t -> t.playlistItemId == id }.uri, it.id) } },
                            onItemClick = { settings = false; controller.select(it.uri, paths) }, onAddToPlaylist = { addTrack = it.uri },
                            onQueueNext = { controller.queueNext(it.uri) },
                            actions = { track, close ->
                                MidiFileActionsDialog(title = track.title, onDismiss = close,
                                    onPlay = { close(); controller.queueNext(track.uri) },
                                    onShare = { close(); shareTrack = track.uri },
                                    onAddToPlaylist = { close(); addTrack = track.uri })
                            })
                    }
                    }
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
