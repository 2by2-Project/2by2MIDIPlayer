@file:OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class)

package jp.project2by2.musicplayer.ios

import jp.project2by2.musicplayer.MidiMetadata
import jp.project2by2.musicplayer.PianoRollIndex
import jp.project2by2.musicplayer.ios.audio.*
import jp.project2by2.musicplayer.parseMidiMetadata
import jp.project2by2.musicplayer.parseMidiTiming
import jp.project2by2.musicplayer.parseSmfToPianoRollIndex
import jp.project2by2.musicplayer.ui.player.PIANO_ROLL_TIMING_MAX_MS
import jp.project2by2.musicplayer.ui.player.playerText
import jp.project2by2.musicplayer.playback.LOOP_FADE_OUT_DURATION_MS
import jp.project2by2.musicplayer.playback.LoopBoundaryAction
import jp.project2by2.musicplayer.playback.LoopFadePolicy
import jp.project2by2.musicplayer.playback.PlaybackQueue
import jp.project2by2.musicplayer.ui.settings.SoundFontOption
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

data class IosState(
    val settings: IosSettings = IosSettings(),
    val playlists: List<IosPlaylist> = emptyList(),
    /** Library key of the loaded track. */
    val current: String? = null,
    /** Folder or playlist the queue came from; the artist line falls back to it. */
    val sourceTitle: String? = null,
    val index: PianoRollIndex? = null,
    val audio: EnginePosition = EnginePosition(),
    val audioReady: Boolean = false,
    val soundFontLoading: Boolean = false,
    val soundFontDownloadProgress: Float? = null,
    val busy: Boolean = false,
    val metadata: Map<String, MidiMetadata> = emptyMap(),
    val demoFiles: List<String> = emptyList(),
    val browseDir: String = "doc:",
    /** Bumped when files change (rename, import, linked folders) so listings are read again. */
    val libraryRevision: Int = 0,
    /** Output latency the piano roll compensates (Bluetooth), in ms. */
    val outputDelayMs: Int = 0,
    val message: UserMessage? = null,
    /** Bumped when playback starts from a tap, so the compact player opens like Android. */
    val showPlayerRequest: Int = 0,
)

data class UserMessage(val id: Long, val text: String)

/**
 * The iOS counterpart of Android's PlaybackService plus MainActivity state: the play queue with
 * Android's loop, fade and shuffle rules, settings, playlists and the file library.
 * BASS runs on one dedicated thread; UI state is published through [state].
 */
class IosController(
    val store: IosStore = IosStore(),
    val library: IosLibrary = IosLibrary(store),
    private val engine: MidiEngine = BassEngine(),
) {
    private val engineContext = newSingleThreadContext("bass-control")
    private val scope = CoroutineScope(SupervisorJob() + engineContext)
    private val mutable = MutableStateFlow(IosState())
    val state = mutable.asStateFlow()
    private val queue = PlaybackQueue()
    private var loaded: String? = null
    private var hasLoopMarker = false
    private var nextMessageId = 0L
    private val trackIds = mutableMapOf<String, Long>()
    // Metadata reads and the cache run one at a time, off the BASS thread.
    private val metadataContext = Dispatchers.Default.limitedParallelism(1)
    private val metadataCache = IosMetadataCache(library.support + "/metadata-cache.plist")
    private var foreground = true
    private var fading = false
    private var resumeAfterInterruption = false

    // Read on the audio thread at loop boundaries. The rule itself is shared with Android (mobileMain).
    private val loopEnabled = AtomicInt(0)
    private val markerPresent = AtomicInt(0)
    private val fadeRequested = AtomicInt(0)
    private val loopFade = LoopFadePolicy()
    private val boundary = LoopBoundary {
        when (loopFade.onBoundary(loopEnabled.load() != 0, markerPresent.load() != 0, fadeRequested.load() != 0)) {
            LoopBoundaryAction.Repeat -> true
            // Without CC#111 the song ends and the polling loop advances the queue.
            LoopBoundaryAction.End -> false
            // The fade itself starts on the control thread (pollPlayback).
            LoopBoundaryAction.RepeatAndFade -> { fadeRequested.store(1); true }
        }
    }

    private val session = AudioSessionController(
        onInterruptionBegan = { operation {
            resumeAfterInterruption = mutable.value.audio.playing
            pauseInternal()
        } },
        onInterruptionEnded = { resume -> operation {
            if (resume && resumeAfterInterruption) playInternal()
            resumeAfterInterruption = false
        } },
        onOutputLost = { operation { pauseInternal() } },
        onRouteChanged = { refreshOutputDelay() },
    )

    private val nowPlaying = NowPlayingCenter(
        onPlay = { operation { playInternal() } },
        onPause = { operation { pauseInternal() } },
        onToggle = { togglePlay() },
        onNext = { next() },
        // Android's notification "previous" goes back to the start of the song.
        onBackToStart = { seek(0) },
        onSeek = { seek(it) },
        onLoop = { setLoop(it) },
        onShuffle = { setShuffle(it) },
    )

    init {
        val settings = store.settings()
        loopEnabled.store(if (settings.loopEnabled) 1 else 0)
        mutable.value = IosState(settings = settings, playlists = store.playlists(),
            browseDir = restoreBrowseFolder(settings.browseFolder))
        trackIds.clear()
        operation {
            session.start()
            nowPlaying.start()
            refreshOutputDelay()
            engine.initialize()
            applySynthSettings()
            settings.soundFontFile?.let { name ->
                val path = soundFontDirectory() + "/" + name
                if (platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath(path)) {
                    runCatching { engine.setSoundFont(path) }.onFailure { report(it) }
                }
            }
            mutable.update { it.copy(audioReady = true) }
            updateNowPlaying()
        }
        scope.launch(metadataContext) { metadataCache.load() }
        scope.launch { pollPlayback() }
    }

    // region Library

    private fun restoreBrowseFolder(saved: String?): String {
        // A folder removed since then opens its nearest remaining parent.
        var key: String? = saved?.takeIf { !library.isDemo(it) }
        while (key != null) {
            if (library.isDirectory(key)) return key
            key = library.parent(key)
        }
        return library.home
    }

    fun browse(dir: String) {
        if (mutable.value.browseDir == dir) return
        mutable.update { it.copy(browseDir = dir) }
        // The sample folder is not on disk, so it never replaces the saved location.
        if (!library.isDemo(dir)) saveSettings { it.copy(browseFolder = dir) }
    }

    fun trackId(key: String): Long = trackIds.getOrPut(key) { trackIds.size + 1L }

    /** Reads titles for listed tracks, from the cache when the file is unchanged (Android's Room cache). */
    fun loadMetadata(keys: List<String>) {
        val known = mutable.value.metadata
        val missing = keys.filter { it !in known }.distinct()
        if (missing.isEmpty()) return
        scope.launch(metadataContext) {
            val batch = mutableMapOf<String, MidiMetadata>()
            fun flush() {
                if (batch.isEmpty()) return
                val found = batch.toMap()
                batch.clear()
                mutable.update { it.copy(metadata = it.metadata + found) }
            }
            for (key in missing) {
                ensureActive()
                if (key in mutable.value.metadata) continue
                val path = library.resolve(key) ?: continue
                val metadata = metadataCache.get(key, path) ?: library.readBytesIfLocal(key)
                    ?.let { bytes -> runCatching { parseMidiMetadata(bytes) }.getOrNull() }
                    ?.also { metadataCache.put(key, path, it) }
                    ?: continue
                batch[key] = metadata
                // Publish in groups: one state change per song would recompose a large list repeatedly.
                if (batch.size >= 25) flush()
            }
            flush()
            metadataCache.save()
        }
    }

    fun loadDemos() {
        if (mutable.value.demoFiles.isNotEmpty()) return
        val demos = library.demos()
        mutable.update { it.copy(demoFiles = demos) }
        loadMetadata(demos)
    }

    fun renameFolder(dir: String, name: String) = operation {
        val renamed = library.rename(dir, name)
        if (renamed == dir) return@operation
        fun remap(value: String) = if (value == dir || value.startsWith("$dir/")) renamed + value.removePrefix(dir) else value
        val active = loaded?.takeIf { remap(it) != it }
        queue.remap(::remap)
        if (active != null) loaded = remap(active)
        mutable.update { state -> state.copy(
            current = state.current?.let(::remap),
            browseDir = remap(state.browseDir),
            playlists = state.playlists.map { list -> list.copy(tracks = list.tracks.map(::remap)) },
            metadata = state.metadata.mapKeys { remap(it.key) },
            libraryRevision = state.libraryRevision + 1,
        ) }
        store.savePlaylists(mutable.value.playlists)
        saveSettings { it.copy(browseFolder = mutable.value.browseDir) }
    }

    fun addLinkedFolder(url: NSURL) = operation {
        val key = library.addLinkedFolder(url)
        mutable.update { it.copy(libraryRevision = it.libraryRevision + 1) }
        browse(key)
    }

    fun removeLinkedFolder(key: String) = operation {
        library.removeLinkedFolder(key)
        mutable.update { it.copy(libraryRevision = it.libraryRevision + 1) }
        if (library.rootId(mutable.value.browseDir) == library.rootId(key)) browse(library.home)
    }

    fun refreshLibrary() {
        library.reloadLinkedFolders()
        mutable.update { it.copy(libraryRevision = it.libraryRevision + 1) }
    }

    /** Files from other apps ("Open in", AirDrop) or the picker play as a temporary queue. */
    fun openFiles(urls: List<NSURL>) = operation {
        val keys = urls.mapNotNull { url ->
            runCatching { library.importFile(url) }.onFailure { report(it) }.getOrNull()
        }.filter { IosLibrary.isMidiName(library.fileName(it)) }.distinct()
        if (keys.isEmpty()) return@operation
        mutable.update { it.copy(libraryRevision = it.libraryRevision + 1) }
        loadMetadata(keys)
        queue.set(keys, library.label(library.parent(keys.first()) ?: library.home))
        startTrack(keys.first(), queue.title)
    }

    // endregion

    /** Values for the shared file details screen. Call off the main thread. */
    fun fileDetails(key: String): jp.project2by2.musicplayer.ui.details.FileDetails {
        val metadata = metadataFor(key)
        val name = library.fileName(key)
        val extension = name.substringAfterLast('.', "")
        return jp.project2by2.musicplayer.ui.details.FileDetails(
            displayName = name,
            parentFolder = if (library.isDemo(key)) text("folder_demo_name") else library.parent(key)?.let(library::label),
            format = platform.UniformTypeIdentifiers.UTType.typeWithFilenameExtension(extension)?.preferredMIMEType,
            durationMs = metadata?.durationMs ?: 0L,
            loopPointMs = metadata?.loopPointMs,
            midiTitle = metadata?.title,
            midiArtist = metadata?.copyright,
        )
    }

    private fun metadataFor(key: String): MidiMetadata? = mutable.value.metadata[key]
        ?: runCatching { parseMidiMetadata(library.readBytes(key)) }.getOrNull()?.also { metadata ->
            mutable.update { it.copy(metadata = it.metadata + (key to metadata)) }
        }

    /**
     * Opens the shared loop point editor: playback pauses (as Android does) and the file is prepared
     * with a separate preview stream. Call off the main thread.
     */
    fun loopEditorSource(key: String, preview: IosLoopPreview): jp.project2by2.musicplayer.ui.loop.LoopEditorSource? {
        pause()
        val bytes = runCatching { library.readBytes(key) }.getOrNull() ?: return null
        val index = parseSmfToPianoRollIndex(bytes) ?: return null
        val initial = jp.project2by2.musicplayer.loopEditorInitialData(index)
        val font = mutable.value.settings.soundFontFile?.let { soundFontDirectory() + "/" + it }
        val path = library.resolve(key)
        val previewLoaded = font != null && path != null &&
            preview.load(path, font, initial.loopPointMs, initial.pianoRollData.totalDurationMs)
        return jp.project2by2.musicplayer.ui.loop.LoopEditorSource(library.fileName(key), initial, preview.takeIf { previewLoaded })
    }

    /** Writes the edited copy (Android exports through the Storage Access Framework) where the user chooses. */
    suspend fun exportLoopEdit(key: String, name: String, loopTick: Int, endTick: Int): jp.project2by2.musicplayer.ui.loop.LoopExportResult {
        val bytes = withContext(Dispatchers.Default) {
            runCatching { library.readBytes(key) }.getOrNull()?.let { jp.project2by2.musicplayer.buildLoopPointMidi(it, loopTick, endTick) }
        } ?: return jp.project2by2.musicplayer.ui.loop.LoopExportResult.SerializationFailed
        val directory = NSTemporaryDirectory().trimEnd('/') + "/loop-export-" + NSUUID().UUIDString
        val files = NSFileManager.defaultManager
        files.createDirectoryAtPath(directory, true, null, null)
        val path = "$directory/${name.replace('/', '_')}"
        try {
            if (!bytes.toNSData().writeToFile(path, atomically = true)) return jp.project2by2.musicplayer.ui.loop.LoopExportResult.Failed
            val chosen = CompletableDeferred<Boolean?>()
            IosPlatformUi.exportFile(path) { chosen.complete(it) }
            return when (chosen.await()) {
                true -> {
                    // The copy may have been saved into the library (Documents or a linked folder).
                    refreshLibrary()
                    jp.project2by2.musicplayer.ui.loop.LoopExportResult.Saved
                }
                false -> jp.project2by2.musicplayer.ui.loop.LoopExportResult.Failed
                null -> jp.project2by2.musicplayer.ui.loop.LoopExportResult.Cancelled
            }
        } finally {
            files.removeItemAtPath(directory, null)
        }
    }

    // region Playback

    /** A tap in Browse, search or a playlist: the list becomes the queue (Android's handleMidiTap). */
    fun select(key: String, items: List<String>, sourceTitle: String?, playlistId: String? = null) = operation {
        if (!engine.hasSoundFont) {
            message(text("error_soundfont_not_set"))
            return@operation
        }
        queue.set(items.ifEmpty { listOf(key) }, sourceTitle, playlistId)
        startTrack(key, sourceTitle)
    }

    fun playPlaylist(id: String) {
        val playlist = mutable.value.playlists.firstOrNull { it.id == id } ?: return
        val first = playlist.tracks.firstOrNull() ?: return
        select(first, playlist.tracks, playlist.name, playlist.id)
    }

    private fun startTrack(key: String, sourceTitle: String?) {
        if (!engine.hasSoundFont) {
            message(text("error_soundfont_not_set"))
            return
        }
        try {
            loadTrack(key, sourceTitle)
            playInternal()
            mutable.update { it.copy(showPlayerRequest = it.showPlayerRequest + 1) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message(if (!library.exists(key)) text("error_midi_file_missing") else text("error_failed_to_load_midi") + ": " + (e.message ?: ""))
        }
    }

    private fun loadTrack(key: String, sourceTitle: String?) {
        mutable.update { it.copy(busy = true) }
        try {
            val bytes = library.readBytes(key)
            val index = parseSmfToPianoRollIndex(bytes) ?: error(text("error_failed_to_load_midi"))
            require(index.totalTicks > 0) { text("error_failed_to_load_midi") }
            val timing = runCatching { parseMidiTiming(bytes) }.getOrNull()
            val marker = timing?.loopStartTick != null
            val range = if (marker && timing.endTick > 0) LoopRange.Ticks(timing.loopStartTick!!.toLong(), timing.endTick.toLong())
                else LoopRange.Ticks(0, null)
            cancelFadeState()
            markerPresent.store(if (marker) 1 else 0)
            hasLoopMarker = marker
            engine.unload()
            loaded = null
            engine.load(library.resolve(key)!!, range, boundary)
            loaded = key
            val metadata = runCatching { parseMidiMetadata(bytes) }.getOrNull()
            mutable.update { state -> state.copy(
                current = key, index = index, sourceTitle = sourceTitle ?: queue.title,
                audio = engine.position(),
                metadata = if (metadata != null) state.metadata + (key to metadata) else state.metadata,
            ) }
        } finally {
            mutable.update { it.copy(busy = false) }
        }
    }

    private fun playInternal() {
        if (!engine.loaded) {
            val key = mutable.value.current ?: return
            loadTrack(key, mutable.value.sourceTitle)
        }
        session.activate()
        engine.play()
        mutable.update { it.copy(audio = engine.position()) }
        updateNowPlaying()
    }

    private fun pauseInternal() {
        // An explicit transport command cancels a pending automatic transition, like Android.
        cancelFade()
        engine.pause()
        mutable.update { it.copy(audio = engine.position()) }
        updateNowPlaying()
    }

    fun togglePlay() = operation { if (mutable.value.audio.playing) pauseInternal() else playInternal() }
    fun play() = operation { playInternal() }
    fun pause() = operation { pauseInternal() }

    fun seek(ms: Long) = operation {
        if (!engine.loaded) return@operation
        cancelFade()
        engine.seek(ms.coerceIn(0, mutable.value.audio.durationMs.coerceAtLeast(0)))
        mutable.update { it.copy(audio = engine.position()) }
        updateNowPlaying()
    }

    fun next() = operation { advance(forward = true) }

    /** In-app previous: back to the start after 3 seconds, otherwise the previous song (Android). */
    fun previous() = operation {
        if (engine.position().positionMs > 3000L) {
            cancelFade()
            engine.seek(0)
            mutable.update { it.copy(audio = engine.position()) }
            updateNowPlaying()
        } else advance(forward = false)
    }

    private fun advance(forward: Boolean) {
        val shuffle = mutable.value.settings.shuffleEnabled
        val current = mutable.value.current
        val target = (if (forward) queue.next(current, shuffle) else queue.previous(current, shuffle)) ?: return
        startTrackKeepingPlayer(target)
    }

    private fun startTrackKeepingPlayer(key: String) {
        try {
            loadTrack(key, queue.title)
            playInternal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message(text("error_failed_to_load_midi") + ": " + (e.message ?: ""))
        }
    }

    /** "Play next": inserts after the current song (Android's enqueueNextInQueue). */
    fun queueNext(key: String, fallbackItems: List<String>, fallbackTitle: String?) = operation {
        if (queue.enqueueNext(key, mutable.value.current, fallbackItems, fallbackTitle)) message(text("info_added_to_next_queue"))
        else message(text("info_no_active_queue"))
    }

    private fun cancelFadeState() {
        fading = false
        fadeRequested.store(0)
        loopFade.reset()
    }

    private fun cancelFade() {
        if (fading || fadeRequested.load() != 0) engine.cancelFade()
        cancelFadeState()
    }

    private suspend fun pollPlayback() {
        var lastLoops = 0L
        var lastPlaying = false
        while (currentCoroutineContext().isActive) {
            delay(if (foreground) 40 else 250)
            try {
                if (!engine.loaded) continue
                val previous = mutable.value.audio
                val position = engine.position()
                mutable.update { it.copy(audio = position) }
                // Second CC#111 pass: fade out over 8 s, then the next song (Android's loop-and-fade).
                if (fadeRequested.load() != 0 && !fading) {
                    if (position.playing) {
                        engine.startFade(LOOP_FADE_OUT_DURATION_MS)
                        fading = true
                    } else cancelFadeState()
                }
                if (fading && engine.fadeFinished()) {
                    cancelFadeState()
                    advance(forward = true)
                    continue
                }
                // The song ended without a loop: Android's END sync plays the next song in the queue.
                if (previous.playing && !position.playing && !fading && position.durationMs > 0 &&
                    position.positionMs >= position.durationMs - 200) {
                    advance(forward = true)
                    continue
                }
                val loops = engine.audibleLoopCount()
                if (loops != lastLoops || position.playing != lastPlaying) {
                    lastLoops = loops
                    lastPlaying = position.playing
                    updateNowPlaying()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                engine.unload()
                loaded = null
                mutable.update { it.copy(audio = it.audio.copy(playing = false)) }
                report(e)
            }
        }
    }

    // endregion

    // region Settings

    private fun saveSettings(change: (IosSettings) -> IosSettings) {
        mutable.update { it.copy(settings = change(it.settings)) }
        store.save(mutable.value.settings)
    }

    fun setLoop(enabled: Boolean) {
        loopEnabled.store(if (enabled) 1 else 0)
        saveSettings { it.copy(loopEnabled = enabled) }
        operation { updateNowPlaying() }
    }

    fun setShuffle(enabled: Boolean) {
        saveSettings { it.copy(shuffleEnabled = enabled) }
        operation { updateNowPlaying() }
    }

    fun setMaxVoices(value: Int) { saveSettings { it.copy(maxVoices = value.coerceIn(1, 1000)) }; operation { applySynthSettings() } }
    fun setEffectsEnabled(value: Boolean) { saveSettings { it.copy(effectsEnabled = value) }; operation { applySynthSettings() } }
    fun setReverbStrength(value: Float) {
        if (!value.isFinite()) return
        saveSettings { it.copy(reverbStrength = value.coerceIn(0f, 3f)) }
        operation { applySynthSettings() }
    }
    fun setPianoRollTiming(value: Int) = saveSettings { it.copy(pianoRollTimingMs = value.coerceIn(-PIANO_ROLL_TIMING_MAX_MS, PIANO_ROLL_TIMING_MAX_MS)) }
    fun setFolderViewMode(listMode: Boolean) = saveSettings { it.copy(folderViewMode = if (listMode) 1 else 0) }

    private fun applySynthSettings() = mutable.value.settings.let { engine.synthSettings(it.maxVoices, it.effectsEnabled, it.reverbStrength) }

    private fun refreshOutputDelay() {
        mutable.update { it.copy(outputDelayMs = session.outputDelayMs()) }
    }

    fun setForeground(active: Boolean) {
        foreground = active
        if (active) refreshOutputDelay()
    }

    // endregion

    // region SoundFont

    private fun soundFontDirectory(): String = library.support + "/SoundFonts"

    /** Copies a picked bank into Application Support and makes it active. */
    fun importSoundFont(url: NSURL) = installSoundFont(download = null) {
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            val name = url.lastPathComponent ?: "soundfont.sf2"
            val target = newSoundFontPath(name)
            nsError { platform.Foundation.NSFileManager.defaultManager.copyItemAtPath(url.path!!, target, it) }
            target to name
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    fun downloadSoundFont(option: SoundFontOption, onComplete: (Boolean) -> Unit) = installSoundFont(download = onComplete) {
        val name = option.url.substringAfterLast('/')
        val target = newSoundFontPath(name)
        downloadFile(option.url, target) { progress -> mutable.update { it.copy(soundFontDownloadProgress = progress) } }
        target to option.name
    }

    private fun newSoundFontPath(name: String): String {
        val directory = soundFontDirectory()
        platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val safe = name.filter { it.isLetterOrDigit() || it in "._- " }.ifBlank { "soundfont.sf2" }
        return "$directory/${NSUUID().UUIDString}-$safe"
    }

    private fun installSoundFont(download: ((Boolean) -> Unit)?, source: suspend () -> Pair<String, String>) {
        if (mutable.value.soundFontLoading) return
        mutable.update { it.copy(soundFontLoading = true, soundFontDownloadProgress = null) }
        scope.launch {
            var success = false
            var file: String? = null
            try {
                val (path, name) = withContext(Dispatchers.Default) {
                    val work = currentCoroutineContext()
                    val (copied, name) = source()
                    // DLS banks are converted to SF2 with the shared converter, as on Android.
                    try { convertDlsIfNeeded(copied) { work.ensureActive() } to name }
                    catch (e: Throwable) { platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(copied, null); throw e }
                }
                file = path
                checkSoundFontHeader(path)
                engine.setSoundFont(path)
                val previous = mutable.value.settings.soundFontFile
                saveSettings { it.copy(soundFontFile = path.substringAfterLast('/'), soundFontName = name) }
                file = null
                previous?.takeIf { it != path.substringAfterLast('/') }?.let {
                    platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(soundFontDirectory() + "/" + it, null)
                }
                success = true
                if (download != null) message(text("soundfont_download_success"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(text(if (download != null) "soundfont_download_failed" else "settings_soundfont_import_failed") + ": " + (e.message ?: ""))
            } finally {
                file?.let { platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(it, null) }
                mutable.update { it.copy(soundFontLoading = false, soundFontDownloadProgress = null) }
                download?.invoke(success)
            }
        }
    }

    private fun checkSoundFontHeader(path: String) {
        val handle = platform.Foundation.NSFileHandle.fileHandleForReadingAtPath(path) ?: error("Cannot read SoundFont")
        val header = try { handle.readDataOfLength(12u).toByteArray() } finally { handle.closeFile() }
        val riff = header.size >= 12 && header.decodeToString(0, 4) == "RIFF"
        val form = if (header.size >= 12) header.decodeToString(8, 12) else ""
        require(riff && form == "sfbk") { localized("Not a SoundFont (SF2/SF3/DLS) file", "SoundFont (SF2/SF3/DLS) ファイルではありません") }
    }

    // endregion

    // region Playlists

    private fun savePlaylists(change: (List<IosPlaylist>) -> List<IosPlaylist>) {
        mutable.update { it.copy(playlists = change(it.playlists)) }
        store.savePlaylists(mutable.value.playlists)
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        savePlaylists { it + IosPlaylist(NSUUID().UUIDString, name.trim()) }
    }

    fun renamePlaylist(id: String, name: String) {
        if (name.isBlank()) return
        savePlaylists { lists -> lists.map { if (it.id == id) it.copy(name = name.trim()) else it } }
    }

    fun deletePlaylist(id: String) = savePlaylists { lists -> lists.filterNot { it.id == id } }

    fun addToPlaylist(key: String, id: String) {
        savePlaylists { lists -> lists.map { if (it.id == id) it.copy(tracks = (it.tracks + key).distinct()) else it } }
        message(text("info_added_to_playlist"))
    }

    /** Saves an edited order; an empty list removes every song. */
    fun reorderPlaylist(id: String, tracks: List<String>) =
        savePlaylists { lists -> lists.map { if (it.id == id) it.copy(tracks = tracks.distinct()) else it } }

    // endregion

    // region Messages and helpers

    private fun operation(block: suspend CoroutineScope.() -> Unit) = scope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { report(e) }
    }

    private fun report(error: Throwable) = message(error.message ?: error::class.simpleName ?: "Error")

    fun message(text: String) {
        nextMessageId += 1
        mutable.update { it.copy(message = UserMessage(nextMessageId, text)) }
    }

    fun dismissMessage(id: Long) = mutable.update { if (it.message?.id == id) it.copy(message = null) else it }

    private fun updateNowPlaying() {
        val state = mutable.value
        val key = state.current
        val info = key?.let {
            val metadata = state.metadata[it]
            NowPlayingInfo(
                title = metadata?.title?.takeIf { t -> t.isNotBlank() } ?: library.fileName(it),
                artist = metadata?.copyright?.takeIf { c -> c.isNotBlank() } ?: state.sourceTitle,
                durationMs = state.audio.durationMs,
                positionMs = state.audio.positionMs,
                playing = state.audio.playing,
                artwork = library.parent(it)?.let(library::artworkData),
            )
        }
        val settings = state.settings
        platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
            nowPlaying.update(info, settings.loopEnabled, settings.shuffleEnabled)
        }
    }

    // endregion

}

/** Host text outside composition, from the same tables as playerString. */
internal fun text(key: String): String = playerText(key, iosJapanese)
