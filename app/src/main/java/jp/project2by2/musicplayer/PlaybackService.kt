package jp.project2by2.musicplayer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSMIDI
import jp.project2by2.musicplayer.audio.MidiLoopStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.system.measureTimeMillis

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val commandToggleLoop = SessionCommand(CUSTOM_COMMAND_TOGGLE_LOOP, android.os.Bundle.EMPTY)
    private val binder = LocalBinder()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = Random(System.currentTimeMillis())

    @Volatile private var handles: MidiHandles? = null
    private var loopPoint: LoopPoint? = null
    private var syncProc: BASS.SYNCPROC? = null
    private var syncHandle: Int = 0
    private var volumeSlideSyncHandle: Int = 0
    private var volumeSlideSyncProc: BASS.SYNCPROC? = null
    private val fadeEpoch = AtomicLong()
    private var loopRepeatCount: Int = 0
    private val transitionInProgress = AtomicBoolean(false)
    @Volatile private var loopEnabledSnapshot: Boolean = false
    @Volatile private var shuffleEnabledSnapshot: Boolean = false
    private val midiParser by lazy { MidiParser(contentResolver) }

    // Media session
    private lateinit var bassPlayer: BassPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationProvider: DefaultMediaNotificationProvider

    // Current playing
    private var currentUriString: String? = null
    private var currentTitle: String? = null
    public var currentArtist: String? = null
    public var currentArtworkUri: Uri? = null
    private var activeQueueId: Long? = null
    private var activeQueueTitle: String? = null
    private var activeQueueItems: List<String> = emptyList()
    private var shuffledOrder: List<Int> = emptyList()
    private var shuffledCursor: Int = -1
    private val playlistRepository: PlaylistRepository by lazy {
        PlaylistStore.repository(this)
    }


    // Temporary loop point for editing mode
    @Volatile private var temporaryLoopPointMs: Long? = null
    @Volatile private var temporaryEndPointMs: Long? = null
    private var notificationPlaceholderArtworkUri: Uri? = null
    private var notifiedLoopSource: MidiHandles? = null
    private var notifiedLoopCount = 0L
    private val positionUpdate = object : Runnable {
        override fun run() {
            updateLoopNotification()
            mainHandler.postDelayed(this, 50)
        }
    }

    @Synchronized private fun updateLoopNotification() {
        val source = handles ?: return
        val count = source.audio.audibleLoopCount()
        if (source !== notifiedLoopSource) {
            notifiedLoopSource = source
            notifiedLoopCount = count
        } else if (count != notifiedLoopCount) {
            notifiedLoopCount = count
            bassPlayer.notifyLoopDiscontinuity(source.audio.positionMs())
            bassPlayer.invalidateFromBass()
            triggerNotificationUpdate()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        val initMs = measureTimeMillis {
            bassInit()
            observePlaybackSettings()

            bassPlayer = BassPlayer(
                looper = Looper.getMainLooper(),
                initialTitle = getString(R.string.app_name),
                initialArtworkUri = getOrCreateNotificationPlaceholderArtworkUri(),
                onPlay = { playInternalFromController() },
                onPause = { pauseInternalFromController(releaseFocus = true) },
                onSeek = { ms -> seekInternalFromController(ms) },
                onSeekToPrevious = { seekInternalFromController(0L) },
                onSeekToNext = {
                    serviceScope.launch { playNextInQueue(shuffleEnabledSnapshot) }
                },
                onSetLoopEnabled = { enabled ->
                    serviceScope.launch { setLoopEnabledFromController(enabled) }
                },
                onSetShuffleEnabled = { enabled ->
                    serviceScope.launch { setShuffleEnabledFromController(enabled) }
                },
                queryPositionMs = { getSessionPositionMs() },
                queryDurationMs = { getDurationMs() },
                queryIsPlaying = { isPlaying() },
                queryLoopEnabled = { loopEnabledSnapshot },
                queryShuffleEnabled = { shuffleEnabledSnapshot },
            )
            val contentIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            mediaSession = MediaSession.Builder(this, bassPlayer)
                .setId(getString(R.string.playback_session_id))
                .setSessionActivity(contentIntent)
                .setCallback(mediaSessionCallback)
                .build()

            notificationProvider = DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.notification_channel_name)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setNotificationId(NOTIFICATION_ID)
                .build().also { provider ->
                    provider.setSmallIcon(R.drawable.notification_icon)
                }
            setMediaNotificationProvider(notificationProvider)
            updateNotificationControls()
        }
        Log.d(STARTUP_TRACE_TAG, "PlaybackService.onCreate took ${initMs}ms")
        mainHandler.post(positionUpdate)
    }

    private fun observePlaybackSettings() {
        serviceScope.launch {
            SettingsDataStore.loopEnabledFlow(this@PlaybackService).collectLatest {
                loopEnabledSnapshot = it
                refreshPlayerUiAndNotification()
            }
        }
        serviceScope.launch {
            SettingsDataStore.shuffleEnabledFlow(this@PlaybackService).collectLatest {
                shuffleEnabledSnapshot = it
                refreshPlayerUiAndNotification()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == MediaSessionService.SERVICE_INTERFACE) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(positionUpdate)
        unregisterNoisyReceiver()
        abandonAudioFocus()
        mediaSession.release()
        serviceScope.cancel()
        releaseHandles()
        bassTerminate()
        bassPlayer.release()
        super.onDestroy()
    }

    private fun isPlayingBass(): Boolean {
        val h = handles ?: return false
        return BASS.BASS_ChannelIsActive(h.stream) == BASS.BASS_ACTIVE_PLAYING
    }

    fun loadMidi(uriString: String): Boolean {
        return loadMidiInternal(uriString, null)
    }

    fun loadMidiForEditing(uriString: String, initialLoopMs: Long, initialEndMs: Long): Boolean {
        val seedLoopPoint = LoopPoint(
            startMs = initialLoopMs.coerceAtLeast(0L),
            endMs = initialEndMs.coerceAtLeast(0L),
            hasLoopStartMarker = true
        )
        return loadMidiInternal(uriString, seedLoopPoint)
    }

    @Synchronized
    private fun loadMidiInternal(uriString: String, precomputedLoopPoint: LoopPoint?): Boolean {
        val uri = android.net.Uri.parse(uriString)
        loopRepeatCount = 0
        temporaryLoopPointMs = null
        temporaryEndPointMs = null

        val cacheSoundFontFile = File(cacheDir, SOUND_FONT_FILE)
        if (!cacheSoundFontFile.exists()) {
            return false
        }

        val cacheMidiFile = File(cacheDir, MIDI_FILE)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                cacheMidiFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
        } catch (e: Exception) {
            return false
        }

        releaseHandles()
        handles = bassLoadMidiWithSoundFont(cacheMidiFile.absolutePath, cacheSoundFontFile.absolutePath)
        val h = handles ?: return false
        if (h.stream == 0 || h.font == 0) {
            releaseHandles()
            return false
        }

        // Apply effect stuff
        val (enabled, reverb) = runBlocking {
            val enabled = SettingsDataStore.effectsEnabledFlow(this@PlaybackService).first()
            val reverb = SettingsDataStore.reverbStrengthFlow(this@PlaybackService).first()
            enabled to reverb
        }
        setEffectDisabled(!enabled)
        setReverbStrength(reverb)

        // Apply max voices
        val maxVoices = runBlocking {
            val maxVoices = SettingsDataStore.maxVoicesFlow(this@PlaybackService).first()
            maxVoices
        }
        setMaxVoices(maxVoices)

        // Current playing
        currentUriString = uriString
        val fallbackTitle = resolveDisplayName(uri)
        val fallbackArtist = resolveFolderDisplayName(uri) ?: currentArtist?.takeIf { it.isNotBlank() }
        val metadata = midiParser.getMetadataFromFile(cacheMidiFile)
        currentTitle = metadata.title ?: fallbackTitle
        currentArtist = metadata.copyright ?: fallbackArtist

        // Media3
        bassPlayer.setMetadata(currentTitle!!, currentArtist, artworkUriForMetadata(currentArtworkUri))
        bassPlayer.invalidateFromBass()

        loopPoint = precomputedLoopPoint ?: findLoopPoint(cacheMidiFile)
        val streamLengthMs = h.audio.durationMs
        val lp = loopPoint
        if (LOOP_DIAG) {
            Log.d(
                LOOP_TAG,
                "loadMidi uri=$uriString startTick=${lp?.startTick} endTick=${lp?.endTick} " +
                    "startMs=${lp?.startMs} endMs=${lp?.endMs} streamLenMs=$streamLengthMs hasCC111=${lp?.hasLoopStartMarker}"
            )
        }

        BASS.BASS_ChannelSetAttribute(h.stream, BASS.BASS_ATTRIB_VOL, 1f)
        if (LOOP_DIAG) {
            val lenTick = h.audio.durationTicks
            val flags = BASS.BASS_ChannelFlags(h.stream, 0, 0)
            Log.d(LOOP_TAG, "postFlags lenTick=$lenTick flags=$flags")
        }

        refreshBoundarySync()

        return true
    }

    @Synchronized fun play() {
        if (!requestAudioFocus()) {
            return
        }
        registerNoisyReceiver()

        handles?.let {
            if (it.audio.positionMs() >= it.audio.durationMs) it.audio.seek(0)
            BASS.BASS_ChannelPlay(it.stream, false)
        }
        bassPlayer.invalidateFromBass()
    }

    @Synchronized fun pauseInternal(releaseFocus: Boolean) {
        unregisterNoisyReceiver()
        if (releaseFocus) {
            abandonAudioFocus()
        }

        handles?.let {
            BASS.BASS_ChannelPause(it.stream)
            cancelFade(it)
        }
        bassPlayer.invalidateFromBass()
    }
    fun pause() = pauseInternal(releaseFocus = true)

    @Synchronized fun stop() {
        unregisterNoisyReceiver()
        abandonAudioFocus()

        handles?.let {
            BASS.BASS_ChannelPause(it.stream)
            cancelFade(it)
            it.audio.seek(0)
        }
        bassPlayer.invalidateFromBass()
    }

    fun getCurrentPositionMs(): Long {
        return readRawPositionMs()
    }

    @Synchronized private fun readRawPositionMs(): Long {
        return handles?.audio?.positionMs() ?: 0L
    }

    private fun getSessionPositionMs(): Long = readRawPositionMs()

    @Synchronized fun setCurrentPositionMs(ms: Long) {
        val h = handles ?: return
        cancelFade(h)
        h.audio.seek(ms)
        bassPlayer.invalidateFromBass()
    }

    fun getDurationMs(): Long {
        return handles?.audio?.durationMs ?: 0L
    }

    @Synchronized fun setEffectDisabled(value: Boolean) {
        val h = handles ?: return
        val flagsToSet = if (value) BASSMIDI.BASS_MIDI_NOFX else 0
        h.audio.setFlags(
            flagsToSet,
            BASSMIDI.BASS_MIDI_NOFX
        )
    }

    @Synchronized fun setReverbStrength(value: Float) {
        handles?.let {
            it.audio.setAttribute(
                BASSMIDI.BASS_ATTRIB_MIDI_REVERB,
                value
            )
        }
    }

    @Synchronized fun setMaxVoices(value: Int) {
        handles?.let {
            it.audio.setAttribute(
                BASSMIDI.BASS_ATTRIB_MIDI_VOICES,
                value.toFloat()
            )
        }
    }

    fun getLoopPoint(): LoopPoint? = loopPoint

    @Synchronized fun setTemporaryLoopPoint(loopMs: Long?) {
        temporaryLoopPointMs = loopMs
        refreshBoundarySync()
    }

    fun getTemporaryLoopPoint(): Long? = temporaryLoopPointMs

    @Synchronized fun setTemporaryEndPoint(endMs: Long?) {
        temporaryEndPointMs = endMs?.coerceAtLeast(0L)
        refreshBoundarySync()
    }

    fun getTemporaryEndPoint(): Long? = temporaryEndPointMs

    fun isPlaying(): Boolean {
        val h = handles ?: return false
        return BASS.BASS_ChannelIsActive(h.stream) == BASS.BASS_ACTIVE_PLAYING
    }

    @Synchronized
    private fun releaseHandles() {
        val previous = handles
        handles = null // Invalidate boundary callbacks before waiting for the output to stop.
        fadeEpoch.incrementAndGet()
        previous?.let {
            if (syncHandle != 0) {
                BASS.BASS_ChannelRemoveSync(it.stream, syncHandle)
                syncHandle = 0
            }
            if (volumeSlideSyncHandle != 0) {
                BASS.BASS_ChannelRemoveSync(it.stream, volumeSlideSyncHandle)
                volumeSlideSyncHandle = 0
            }
            it.audio.close()
            BASSMIDI.BASS_MIDI_FontFree(it.font)
        }
        loopRepeatCount = 0
        syncProc = null
        volumeSlideSyncProc = null
        transitionInProgress.set(false)
        handles = null
    }

    // Called inside PCM rendering. Never call MidiLoopStream control methods here:
    // a control thread may be waiting on the native output lock.
    private fun handlePlaybackBoundary(lp: LoopPoint, streamHandle: Int): Boolean {
        if (handles?.stream != streamHandle) return false
        val temporary = temporaryLoopPointMs != null || temporaryEndPointMs != null
        if (temporary || loopEnabledSnapshot) {
            loopRepeatCount = 0
            return true
        }
        if (!lp.hasLoopStartMarker) return false // Output END sync advances the queue at playtime.
        if (transitionInProgress.get()) return true // Keep looping underneath the full track fade.
        if (loopRepeatCount < LOOP_REPEAT_BEFORE_FADE_COUNT) {
            loopRepeatCount += 1
            return true
        }
        loopRepeatCount = 0
        fadeOutThenPlayNext(streamHandle, shuffleEnabledSnapshot)
        return true
    }

    @Synchronized
    private fun refreshBoundarySync() {
        val h = handles ?: return
        val lp = loopPoint ?: return
        val outputHandle = h.stream
        val repeat = { handlePlaybackBoundary(lp, outputHandle) }
        if (temporaryLoopPointMs != null || temporaryEndPointMs != null) {
            val end = (temporaryEndPointMs ?: h.audio.durationMs).coerceIn(0L, h.audio.durationMs)
            val start = (temporaryLoopPointMs ?: lp.startMs).coerceIn(0L, (end - 1).coerceAtLeast(0L))
            h.audio.configure(start, end, repeat)
        } else if (lp.hasLoopStartMarker && lp.endTick > 0) {
            h.audio.configureTicks(lp.startTick.toLong(), lp.endTick.toLong(), repeat)
        } else if (lp.hasLoopStartMarker && lp.endMs > 0) {
            // The editor can provide positions before the MIDI has been parsed into ticks.
            h.audio.configure(lp.startMs, lp.endMs, repeat)
        } else {
            h.audio.configureTicks(0, h.audio.durationTicks, repeat)
        }
        if (syncHandle == 0) {
            // Queue transitions belong to the consumed output boundary, not the decode-ahead boundary.
            syncProc = BASS.SYNCPROC { _, channel, _, _ ->
                if (handles === h && h.audio.failure == null) {
                    val epoch = fadeEpoch.get()
                    serviceScope.launch {
                        if (h.stream == channel) playNextInQueueNow(shuffleEnabledSnapshot, source = h, epoch = epoch)
                    }
                }
            }
            syncHandle = BASS.BASS_ChannelSetSync(h.stream, BASS.BASS_SYNC_END, 0, syncProc, null)
        }
    }

    private fun fadeOutThenPlayNext(
        streamHandle: Int,
        shuffleEnabled: Boolean,
        fadeDurationMs: Int = FADE_OUT_DURATION_MS,
        alreadyLocked: Boolean = false
    ) {
        if (!alreadyLocked && !transitionInProgress.compareAndSet(false, true)) return
        if (handles?.stream != streamHandle) {
            transitionInProgress.set(false)
            return
        }
        val source = handles ?: return
        val epoch = fadeEpoch.incrementAndGet()

        if (volumeSlideSyncHandle != 0) {
            BASS.BASS_ChannelRemoveSync(streamHandle, volumeSlideSyncHandle)
            volumeSlideSyncHandle = 0
        }

        val sliding = BASS.BASS_ChannelSlideAttribute(
            streamHandle,
            BASS.BASS_ATTRIB_VOL,
            0f,
            fadeDurationMs
        )
        if (!sliding) {
            transitionInProgress.set(false)
            serviceScope.launch {
                playNextInQueueNow(shuffleEnabled, source = source, epoch = epoch)
            }
            return
        }

        volumeSlideSyncProc = BASS.SYNCPROC { _, _, _, _ ->
            serviceScope.launch {
                playNextInQueueNow(shuffleEnabled, alreadyLocked = true, source = source, epoch = epoch)
            }
        }
        volumeSlideSyncHandle = BASS.BASS_ChannelSetSync(
            streamHandle,
            BASS.BASS_SYNC_SLIDE,
            BASS.BASS_ATTRIB_VOL.toLong(),
            volumeSlideSyncProc,
            0
        )
    }

    // BASS attribute slides continue even when paused. Cancel the old automatic transition
    // on explicit transport commands, otherwise a paused/stopped track can start the next one.
    private fun cancelFade(h: MidiHandles) {
        BASS.BASS_ChannelLock(h.stream, true)
        try {
            fadeEpoch.incrementAndGet()
            if (volumeSlideSyncHandle != 0) {
                BASS.BASS_ChannelRemoveSync(h.stream, volumeSlideSyncHandle)
                volumeSlideSyncHandle = 0
                volumeSlideSyncProc = null
                BASS.BASS_ChannelSlideAttribute(h.stream, BASS.BASS_ATTRIB_VOL, 1f, 0)
            }
            loopRepeatCount = 0
            transitionInProgress.set(false)
        } finally { BASS.BASS_ChannelLock(h.stream, false) }
    }

    fun setTransientQueue(items: List<String>, title: String?, startUri: String? = null): Boolean {
        val normalized = items.distinct().filter { it.isNotBlank() }
        if (normalized.isEmpty()) return false
        activeQueueId = null
        activeQueueTitle = title?.takeIf { it.isNotBlank() }
        activeQueueItems = normalized
        resetShuffleState()
        currentArtist = activeQueueTitle
        if (startUri != null && normalized.none { it == startUri }) return false
        return true
    }

    fun setActiveQueue(queueId: Long, startUri: String? = null): Boolean {
        val tracks = runBlocking { playlistRepository.getPlaylistItems(queueId) }
        val queue = tracks.sortedBy { it.position }.map { it.uriString }.distinct().filter { it.isNotBlank() }
        if (queue.isEmpty()) return false
        if (startUri != null && queue.none { it == startUri }) return false
        activeQueueId = queueId
        activeQueueTitle = runBlocking { playlistRepository.getPlaylistName(queueId) }
        activeQueueItems = queue
        resetShuffleState()
        currentArtist = activeQueueTitle
        return true
    }

    fun enqueueNextInQueue(
        uriString: String,
        fallbackItems: List<String> = emptyList(),
        fallbackTitle: String? = null
    ): Boolean {
        val target = uriString.trim()
        if (target.isBlank()) return false
        val current = currentUriString ?: return false

        var queue = activeQueueItems
        if (queue.isEmpty()) {
            val normalizedFallback = fallbackItems.distinct().filter { it.isNotBlank() }
            queue = if (normalizedFallback.contains(current)) {
                activeQueueId = null
                activeQueueTitle = fallbackTitle?.takeIf { it.isNotBlank() }
                currentArtist = activeQueueTitle
                normalizedFallback
            } else {
                listOf(current)
            }
        }

        val mutable = queue.toMutableList()
        var currentIndex = mutable.indexOf(current)
        if (currentIndex < 0) {
            mutable.add(0, current)
            currentIndex = 0
        }
        mutable.add(currentIndex + 1, target)

        activeQueueItems = mutable
        resetShuffleState()
        return true
    }

    suspend fun playNextInQueue(shuffleEnabled: Boolean, alreadyLocked: Boolean = false) {
        playNextInQueueNow(shuffleEnabled, alreadyLocked)
    }

    @Synchronized
    private fun playNextInQueueNow(
        shuffleEnabled: Boolean, alreadyLocked: Boolean = false,
        source: MidiHandles? = null, epoch: Long? = null,
    ) {
        if (source != null && handles !== source) return
        if (epoch != null && fadeEpoch.get() != epoch) return
        if (!alreadyLocked && !transitionInProgress.compareAndSet(false, true)) return

        val nextUriString = findNextUriInActiveQueue(shuffleEnabled)
        if (nextUriString == null) {
            transitionInProgress.set(false)
            return
        }

        val loaded = loadMidi(nextUriString)
        if (loaded) {
            val loadedSource = handles
            val loadedEpoch = fadeEpoch.get()
            mainHandler.post {
                synchronized(this@PlaybackService) {
                    if (handles === loadedSource && fadeEpoch.get() == loadedEpoch) {
                        play()
                        bassPlayer.invalidateFromBass()
                        triggerNotificationUpdate()
                    }
                }
            }
        }
        transitionInProgress.set(false)
    }

    suspend fun playPreviousInQueue(shuffleEnabled: Boolean, alreadyLocked: Boolean = false) {
        if (!alreadyLocked && !transitionInProgress.compareAndSet(false, true)) return

        val previousUriString = findPreviousUriInActiveQueue(shuffleEnabled)
        if (previousUriString == null) {
            transitionInProgress.set(false)
            return
        }

        val loaded = loadMidi(previousUriString)
        if (loaded) {
            mainHandler.post {
                play()
                bassPlayer.invalidateFromBass()
                triggerNotificationUpdate()
            }
        }
        transitionInProgress.set(false)
    }

    // Compatibility wrappers for callers not yet migrated.
    suspend fun playNextTrackInCurrentFolder(shuffleEnabled: Boolean, alreadyLocked: Boolean = false) {
        playNextInQueue(shuffleEnabled = shuffleEnabled, alreadyLocked = alreadyLocked)
    }

    suspend fun playPreviousTrackInCurrentFolder(shuffleEnabled: Boolean, alreadyLocked: Boolean = false) {
        playPreviousInQueue(shuffleEnabled = shuffleEnabled, alreadyLocked = alreadyLocked)
    }

    private fun findNextUriInActiveQueue(shuffleEnabled: Boolean): String? {
        val queue = activeQueueItems
        if (queue.isEmpty()) return null
        val current = currentUriString
        val currentIndex = if (current != null) queue.indexOf(current) else -1
        if (currentIndex < 0) return queue.first()

        if (shuffleEnabled) {
            return findFromShuffledOrder(queue, currentIndex, forward = true)
        }

        return queue[(currentIndex + 1) % queue.size]
    }

    private fun findPreviousUriInActiveQueue(shuffleEnabled: Boolean): String? {
        val queue = activeQueueItems
        if (queue.isEmpty()) return null
        val current = currentUriString
        val currentIndex = if (current != null) queue.indexOf(current) else -1
        if (currentIndex < 0) return queue.last()

        if (shuffleEnabled) {
            return findFromShuffledOrder(queue, currentIndex, forward = false)
        }

        val previousIndex = if (currentIndex == 0) queue.lastIndex else currentIndex - 1
        return queue[previousIndex]
    }

    private fun findFromShuffledOrder(queue: List<String>, currentIndex: Int, forward: Boolean): String {
        if (queue.size <= 1) return queue.first()

        ensureShuffleOrder(queue, currentIndex)
        val order = shuffledOrder
        if (order.isEmpty()) return queue[currentIndex]

        val cursor = if (shuffledCursor in order.indices) shuffledCursor else 0
        val nextCursor = if (forward) {
            (cursor + 1) % order.size
        } else {
            if (cursor == 0) order.lastIndex else cursor - 1
        }
        shuffledCursor = nextCursor
        return queue[order[nextCursor]]
    }

    private fun ensureShuffleOrder(queue: List<String>, currentIndex: Int) {
        val queueSizeChanged = shuffledOrder.size != queue.size
        val outOfRange = shuffledOrder.any { it !in queue.indices }
        if (queueSizeChanged || outOfRange || shuffledOrder.isEmpty()) {
            rebuildShuffleOrder(queue, currentIndex)
            return
        }

        val located = shuffledOrder.indexOf(currentIndex)
        if (located >= 0) {
            shuffledCursor = located
        } else {
            rebuildShuffleOrder(queue, currentIndex)
        }
    }

    private fun rebuildShuffleOrder(queue: List<String>, currentIndex: Int) {
        val others = queue.indices.filter { it != currentIndex }.toMutableList()
        others.shuffle(random)
        shuffledOrder = listOf(currentIndex) + others
        shuffledCursor = 0
    }

    private fun resetShuffleState() {
        shuffledOrder = emptyList()
        shuffledCursor = -1
    }

    private fun bassInit(): Boolean {
        var ok = false
        val durationMs = measureTimeMillis {
            ok = BassRuntime.acquire()
        }
        Log.d(STARTUP_TRACE_TAG, "BassRuntime.acquire took ${durationMs}ms")
        return ok
    }

    private fun bassTerminate() {
        BassRuntime.release()
    }

    private fun bassLoadMidiWithSoundFont(midiPath: String, sf2Path: String): MidiHandles? {
        val soundFontHandle = BASSMIDI.BASS_MIDI_FontInit(sf2Path, 0)
        if (soundFontHandle == 0) return null
        return try {
            MidiHandles(createAndroidLoopStream(midiPath, soundFontHandle), soundFontHandle)
        } catch (error: Exception) {
            BASSMIDI.BASS_MIDI_FontFree(soundFontHandle)
            Log.e("MidiLoop", "Cannot load MIDI", error)
            null
        }
    }

    /** Main-thread commit of a validated font. The caller retains ownership if this throws. */
    @Synchronized fun installSoundFont(font: Int, commitFile: () -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val current = handles
        try {
            current?.audio?.setFont(font)
            commitFile()
        } catch (failure: Exception) {
            try { current?.audio?.setFont(current.font) }
            catch (restore: Exception) {
                // Both decoders must stop before the rejected font can be freed.
                releaseHandles()
                failure.addSuppressed(restore)
            }
            throw failure
        }
        if (current == null) return false
        val old = current.font
        current.font = font
        BASSMIDI.BASS_MIDI_FontFree(old)
        return true
    }

    fun getCurrentUriString(): String? = currentUriString
    fun getCurrentTitle(): String? = currentTitle

    private fun resolveDisplayName(uri: android.net.Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }

        return uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.unknown)
    }

    private fun resolveFolderDisplayName(uri: Uri): String? {
        activeQueueTitle?.let { if (it.isNotBlank()) return it }
        if (DemoMidiContract.isDemoUri(uri)) {
            return getString(R.string.folder_demo_name)
        }
        val lastSegment = uri.path?.substringBeforeLast('/', "")?.substringAfterLast('/', "")
        return lastSegment?.takeIf { it.isNotBlank() }
    }

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    private var hasAudioFocus = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }
    private var noisyReceiverRegistered = false

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val result = if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            noisyReceiverRegistered = false
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pauseInternal(releaseFocus = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = isPlaying()
                pauseInternal(releaseFocus = false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private fun setLoopEnabledFromController(enabled: Boolean) {
        loopEnabledSnapshot = enabled
        runBlocking {
            SettingsDataStore.setLoopEnabled(this@PlaybackService, enabled)
        }
        refreshPlayerUiAndNotification()
    }

    private fun setShuffleEnabledFromController(enabled: Boolean) {
        shuffleEnabledSnapshot = enabled
        runBlocking {
            SettingsDataStore.setShuffleEnabled(this@PlaybackService, enabled)
        }
        refreshPlayerUiAndNotification()
    }

    private fun updateNotificationControls() {
        val apply = {
            val buttons = buildNotificationButtons()
            mediaSession.setMediaButtonPreferences(buttons)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            mainHandler.post(apply)
        }
    }

    private fun refreshPlayerUiAndNotification() {
        val apply = {
            updateNotificationControls()
            bassPlayer.invalidateFromBass()
            triggerNotificationUpdate()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            mainHandler.post(apply)
        }
    }

    private fun buildNotificationButtons(): List<CommandButton> {
        val loopIcon = if (loopEnabledSnapshot) CommandButton.ICON_REPEAT_ALL else CommandButton.ICON_REPEAT_OFF
        val loopLabel = if (loopEnabledSnapshot) "Loop on" else "Loop off"
        val shuffleIcon = if (shuffleEnabledSnapshot) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        val shuffleLabel = if (shuffleEnabledSnapshot) "Shuffle on" else "Shuffle off"
        return listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setDisplayName("Back to start")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setDisplayName("Next track")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(loopIcon)
                .setSessionCommand(commandToggleLoop)
                .setDisplayName(loopLabel)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(shuffleIcon)
                .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
                .setDisplayName(shuffleLabel)
                .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build()
        )
    }

    private fun artworkUriForMetadata(sourceArtworkUri: Uri?): Uri? {
        return sourceArtworkUri ?: getOrCreateNotificationPlaceholderArtworkUri()
    }

    private fun getOrCreateNotificationPlaceholderArtworkUri(): Uri? {
        notificationPlaceholderArtworkUri?.let { return it }
        val output = File(cacheDir, "notification_placeholder_art.png")
        if (!output.exists()) {
            runCatching {
                val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.parseColor("#9E9E9E"))
                output.outputStream().use { stream ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.flush()
                }
                bmp.recycle()
            }.onFailure {
                return null
            }
        }
        return Uri.fromFile(output).also { notificationPlaceholderArtworkUri = it }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(commandToggleLoop)
                .build()
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setMediaButtonPreferences(buildNotificationButtons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                CUSTOM_COMMAND_TOGGLE_LOOP -> {
                    serviceScope.launch { setLoopEnabledFromController(!loopEnabledSnapshot) }
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val MIDI_FILE = "midi.mid"
        private const val SOUND_FONT_FILE = "soundfont.sf2"
        private const val LOOP_REPEAT_BEFORE_FADE_COUNT = 1
        private const val FADE_OUT_DURATION_MS = 8000
        private const val CUSTOM_COMMAND_TOGGLE_LOOP = "jp.project2by2.musicplayer.command.TOGGLE_LOOP"
        fun findLoopPoint(midiFile: File): LoopPoint = try {
            val timing = parseMidiTiming(midiFile.readBytes())
            LoopPoint(
                startTick = timing.loopStartTick ?: 0,
                startMs = timing.loopStartMs ?: 0L,
                endTick = timing.endTick,
                endMs = timing.endMs,
                hasLoopStartMarker = timing.loopStartTick != null
            )
        } catch (_: Exception) {
            // Parse errors should not crash playback.
            LoopPoint()
        }

        private const val LOOP_TAG = "LoopDiag"
        private const val LOOP_DIAG = true
    }

    @Synchronized private fun playInternalFromController() {
        if (!requestAudioFocus()) {
            bassPlayer.invalidateFromBass()
            return
        }
        registerNoisyReceiver()
        handles?.let {
            if (it.audio.positionMs() >= it.audio.durationMs) it.audio.seek(0)
            BASS.BASS_ChannelPlay(it.stream, false)
        }
        bassPlayer.invalidateFromBass()
    }

    @Synchronized private fun pauseInternalFromController(releaseFocus: Boolean) {
        handles?.let {
            BASS.BASS_ChannelPause(it.stream)
            cancelFade(it)
        }
        unregisterNoisyReceiver()
        if (releaseFocus) abandonAudioFocus()
        bassPlayer.invalidateFromBass()
    }

    private fun seekInternalFromController(ms: Long) {
        setCurrentPositionMs(ms)
        bassPlayer.invalidateFromBass()
    }

}

data class LoopPoint(
    var startMs: Long = 0L,
    var endMs: Long = -1L,
    var startTick: Int = 0,
    var endTick: Int = -1,
    var hasLoopStartMarker: Boolean = false,
)

data class MidiHandles(
    val audio: MidiLoopStream,
    var font: Int
) {
    val stream: Int get() = audio.output
}
