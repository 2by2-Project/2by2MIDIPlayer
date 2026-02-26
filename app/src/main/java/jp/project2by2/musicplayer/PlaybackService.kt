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
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSMIDI
import dev.atsushieno.ktmidi.Midi1Music
import dev.atsushieno.ktmidi.Midi1Event
import dev.atsushieno.ktmidi.Midi1SimpleMessage
import dev.atsushieno.ktmidi.MidiChannelStatus
import dev.atsushieno.ktmidi.read
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlin.random.Random

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val binder = LocalBinder()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = Random(System.currentTimeMillis())

    private var handles: MidiHandles? = null
    private var loopPoint: LoopPoint? = null
    private var syncProc: BASS.SYNCPROC? = null
    private var syncHandle: Int = 0
    private var volumeSlideSyncHandle: Int = 0
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

    @Volatile private var sessionPositionAnchorMs: Long = 0L
    @Volatile private var sessionAnchorUptimeMs: Long = 0L
    @Volatile private var sessionAnchorValid: Boolean = false
    @Volatile private var sessionLastLoopUptimeMs: Long = 0L

    // Temporary loop point for editing mode
    private var temporaryLoopPointMs: Long? = null
    private var temporaryEndPointMs: Long? = null

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        bassInit()
        observePlaybackSettings()

        bassPlayer = BassPlayer(
            looper = Looper.getMainLooper(),
            initialTitle = getString(R.string.app_name),
            onPlay = { playInternalFromController() },
            onPause = { pauseInternalFromController(releaseFocus = true) },
            onSeek = { ms -> seekInternalFromController(ms) },
            queryPositionMs = { getSessionPositionMs() },
            queryDurationMs = { getDurationMs() },
            queryIsPlaying = { isPlaying() },
        )
        mediaSession = MediaSession.Builder(this, bassPlayer).build()

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
            .build()

        notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.notification_channel_name)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build().also { provider ->
                provider.setSmallIcon(R.drawable.notification_icon)
            }
        setMediaNotificationProvider(notificationProvider)
    }

    private fun observePlaybackSettings() {
        serviceScope.launch {
            SettingsDataStore.loopEnabledFlow(this@PlaybackService).collectLatest {
                loopEnabledSnapshot = it
                applyLoopRuntimeFlags()
                refreshBoundarySync()
            }
        }
        serviceScope.launch {
            SettingsDataStore.shuffleEnabledFlow(this@PlaybackService).collectLatest { shuffleEnabledSnapshot = it }
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

    private fun setBassPositionMs(ms: Long) {
        val h = handles ?: return
        val secs = ms.coerceAtLeast(0L).toDouble() / 1000.0
        val bytes = BASS.BASS_ChannelSeconds2Bytes(h.stream, secs)
        BASS.BASS_ChannelSetPosition(h.stream, bytes, BASS.BASS_POS_BYTE)
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

        val seedLoopPoint = precomputedLoopPoint ?: findLoopPoint(cacheMidiFile)
        if (
            LOOP_DIAG &&
            LOOP_TAIL_PADDING_EXPERIMENT &&
            seedLoopPoint.hasLoopStartMarker &&
            seedLoopPoint.endTick > 0
        ) {
            runCatching {
                val patched = patchCachedMidiTailForBass(
                    midiFile = cacheMidiFile,
                    targetEndTick = seedLoopPoint.endTick
                )
                Log.d(
                    LOOP_TAG,
                    "tailPad applied=$patched targetEndTick=${seedLoopPoint.endTick}"
                )
            }.onFailure {
                Log.d(LOOP_TAG, "tailPad failed: ${it.message}")
            }
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
        bassPlayer.setMetadata(currentTitle!!, currentArtist, currentArtworkUri)
        bassPlayer.invalidateFromBass()

        loopPoint = precomputedLoopPoint ?: findLoopPoint(cacheMidiFile)
        val streamLengthBytes = BASS.BASS_ChannelGetLength(h.stream, BASS.BASS_POS_BYTE)
        val streamLengthMs = (BASS.BASS_ChannelBytes2Seconds(h.stream, streamLengthBytes) * 1000.0).toLong()
        val lp = loopPoint
        if (LOOP_DIAG) {
            Log.d(
                LOOP_TAG,
                "loadMidi uri=$uriString startTick=${lp?.startTick} endTick=${lp?.endTick} " +
                    "startMs=${lp?.startMs} endMs=${lp?.endMs} streamLenMs=$streamLengthMs hasCC111=${lp?.hasLoopStartMarker}"
            )
        }

        BASS.BASS_ChannelSetAttribute(h.stream, BASS.BASS_ATTRIB_VOL, 1f)
        applyLoopRuntimeFlags()
        if (LOOP_DIAG) {
            val lenTick = BASS.BASS_ChannelGetLength(h.stream, BASSMIDI.BASS_POS_MIDI_TICK)
            val flags = BASS.BASS_ChannelFlags(h.stream, 0, 0)
            Log.d(LOOP_TAG, "postFlags lenTick=$lenTick flags=$flags")
        }

        refreshBoundarySync()
        setSessionPositionAnchor(positionMs = 0L)

        return true
    }

    fun play() {
        if (!requestAudioFocus()) {
            return
        }
        registerNoisyReceiver()

        handles?.let { BASS.BASS_ChannelPlay(it.stream, false) }
        setSessionPositionAnchor(readRawPositionMs())
        bassPlayer.invalidateFromBass()
    }

    fun pauseInternal(releaseFocus: Boolean) {
        setSessionPositionAnchor(getSessionPositionMs())
        unregisterNoisyReceiver()
        if (releaseFocus) {
            abandonAudioFocus()
        }

        handles?.let { BASS.BASS_ChannelPause(it.stream) }
        bassPlayer.invalidateFromBass()
    }
    fun pause() = pauseInternal(releaseFocus = true)

    fun stop() {
        unregisterNoisyReceiver()
        abandonAudioFocus()

        handles?.let {
            BASS.BASS_ChannelPause(it.stream)
            BASS.BASS_ChannelSetPosition(it.stream, 0, BASS.BASS_POS_BYTE)
        }
        setSessionPositionAnchor(0L)
        bassPlayer.invalidateFromBass()
    }

    fun getCurrentPositionMs(): Long {
        return readRawPositionMs()
    }

    private fun readRawPositionMs(): Long {
        val h = handles ?: return 0L
        val bytes = BASS.BASS_ChannelGetPosition(h.stream, BASS.BASS_POS_BYTE)
        val secs = BASS.BASS_ChannelBytes2Seconds(h.stream, bytes)
        return (secs * 1000.0).toLong()
    }

    private fun getSessionPositionMs(): Long {
        val now = SystemClock.uptimeMillis()
        val rawMs = readRawPositionMs()
        if (!sessionAnchorValid) {
            setSessionPositionAnchor(rawMs, now)
        }

        val elapsedMs = (now - sessionAnchorUptimeMs).coerceAtLeast(0L)
        var predictedMs = if (isPlayingBass()) {
            sessionPositionAnchorMs + elapsedMs
        } else {
            sessionPositionAnchorMs
        }

        val loopWindow = getActiveLoopWindowMs()
        if (loopWindow != null) {
            predictedMs = wrapPositionInLoopWindow(predictedMs, loopWindow.first, loopWindow.second)
        }

        val durationMs = getDurationMs()
        if (durationMs > 0L) {
            predictedMs = predictedMs.coerceIn(0L, durationMs)
        } else {
            predictedMs = predictedMs.coerceAtLeast(0L)
        }

        // Away from loop edges, snap back to raw if drift grows unexpectedly.
        val elapsedSinceLoop = now - sessionLastLoopUptimeMs
        if (elapsedSinceLoop > 500L && kotlin.math.abs(predictedMs - rawMs) > 1000L) {
            setSessionPositionAnchor(rawMs, now)
            if (LOOP_DIAG) {
                Log.d(LOOP_TAG, "sessionPos drift snap predicted=$predictedMs raw=$rawMs")
            }
            return rawMs
        }

        return predictedMs
    }

    fun setCurrentPositionMs(ms: Long) {
        val h = handles ?: return
        val secs = ms.coerceAtLeast(0L).toDouble() / 1000.0
        val bytes = BASS.BASS_ChannelSeconds2Bytes(h.stream, secs)
        BASS.BASS_ChannelSetPosition(h.stream, bytes, BASS.BASS_POS_BYTE)
        setSessionPositionAnchor(ms.coerceAtLeast(0L))
        bassPlayer.invalidateFromBass()
    }

    fun getDurationMs(): Long {
        val h = handles ?: return 0L
        val bytes = BASS.BASS_ChannelGetLength(h.stream, BASS.BASS_POS_BYTE)
        val secs = BASS.BASS_ChannelBytes2Seconds(h.stream, bytes)
        return (secs * 1000.0).toLong()
    }

    fun setEffectDisabled(value: Boolean) {
        val h = handles ?: return
        val flagsToSet = if (value) BASSMIDI.BASS_MIDI_NOFX else 0
        BASS.BASS_ChannelFlags(
            h.stream,
            flagsToSet,
            BASSMIDI.BASS_MIDI_NOFX
        )
    }

    fun setReverbStrength(value: Float) {
        handles?.let {
            BASS.BASS_ChannelSetAttribute(
                it.stream,
                BASSMIDI.BASS_ATTRIB_MIDI_REVERB,
                value
            )
        }
    }

    fun setMaxVoices(value: Int) {
        handles?.let {
            BASS.BASS_ChannelSetAttribute(
                it.stream,
                BASSMIDI.BASS_ATTRIB_MIDI_VOICES,
                value.toFloat()
            )
        }
    }

    fun getLoopPoint(): LoopPoint? = loopPoint

    fun setTemporaryLoopPoint(loopMs: Long?) {
        temporaryLoopPointMs = loopMs
    }

    fun getTemporaryLoopPoint(): Long? = temporaryLoopPointMs

    fun setTemporaryEndPoint(endMs: Long?) {
        temporaryEndPointMs = endMs?.coerceAtLeast(0L)
        refreshBoundarySync()
    }

    fun getTemporaryEndPoint(): Long? = temporaryEndPointMs

    fun isPlaying(): Boolean {
        val h = handles ?: return false
        return BASS.BASS_ChannelIsActive(h.stream) == BASS.BASS_ACTIVE_PLAYING
    }

    private fun releaseHandles() {
        handles?.let {
            if (syncHandle != 0) {
                BASS.BASS_ChannelRemoveSync(it.stream, syncHandle)
                syncHandle = 0
            }
            if (volumeSlideSyncHandle != 0) {
                BASS.BASS_ChannelRemoveSync(it.stream, volumeSlideSyncHandle)
                volumeSlideSyncHandle = 0
            }
            BASS.BASS_StreamFree(it.stream)
            BASSMIDI.BASS_MIDI_FontFree(it.font)
        }
        loopRepeatCount = 0
        transitionInProgress.set(false)
        handles = null
    }

    private fun handlePlaybackBoundary(lp: LoopPoint, streamHandle: Int) {
        if (handles?.stream != streamHandle) return
        val currentTick = BASS.BASS_ChannelGetPosition(streamHandle, BASSMIDI.BASS_POS_MIDI_TICK).coerceAtLeast(0L)
        if (LOOP_DIAG) {
            val curBytes = BASS.BASS_ChannelGetPosition(streamHandle, BASS.BASS_POS_BYTE)
            val curMs = (BASS.BASS_ChannelBytes2Seconds(streamHandle, curBytes) * 1000.0).toLong()
            Log.d(
                LOOP_TAG,
                "boundary fired curTick=$currentTick curMs=$curMs loopStartTick=${lp.startTick} " +
                    "loopEndTick=${lp.endTick} tempEndMs=$temporaryEndPointMs loopEnabled=$loopEnabledSnapshot"
            )
        }

        // Pseudo boundary for loop-point editor.
        val tempEnd = temporaryEndPointMs
        if (tempEnd != null) {
            val loopTarget = (temporaryLoopPointMs ?: lp.startMs).coerceIn(0L, tempEnd)
            setCurrentPositionMs(loopTarget)
            return
        }

        // Check for temporary loop point (editing mode)
        val tempLoop = temporaryLoopPointMs
        if (tempLoop != null) {
            setCurrentPositionMs(tempLoop)
            return
        }

        val shuffleEnabled = shuffleEnabledSnapshot
        if (loopEnabledSnapshot) {
            loopRepeatCount = 0
            seekToLoopStart(streamHandle, lp)
            return
        }

        if (!lp.hasLoopStartMarker) {
            loopRepeatCount = 0
            serviceScope.launch { playNextInQueue(shuffleEnabled) }
            return
        }

        if (loopRepeatCount < LOOP_REPEAT_BEFORE_FADE_COUNT) {
            loopRepeatCount += 1
            seekToLoopStart(streamHandle, lp)
            return
        }

        loopRepeatCount = 0
        fadeOutFromLoopStartThenPlayNext(streamHandle, lp, shuffleEnabled)
    }

    private fun seekToLoopStart(streamHandle: Int, lp: LoopPoint) {
        // When there is no explicit loop marker and loop start is 0, BASS_SAMPLE_LOOP already wraps to 0.
        // Re-seeking to 0 here can retrigger attacks and cause audible doubling.
        if (!lp.hasLoopStartMarker && lp.startTick <= 0) {
            notifyLooped(0L)
            return
        }
        BASS.BASS_ChannelSetPosition(
            streamHandle,
            lp.startTick.toLong(),
            BASSMIDI.BASS_POS_MIDI_TICK or BASSMIDI.BASS_MIDI_DECAYSEEK
        )
        notifyLooped(lp.startMs)
    }

    private fun fadeOutFromLoopStartThenPlayNext(streamHandle: Int, lp: LoopPoint, shuffleEnabled: Boolean) {
        if (!transitionInProgress.compareAndSet(false, true)) return
        if (handles?.stream != streamHandle) {
            transitionInProgress.set(false)
            return
        }

        seekToLoopStart(streamHandle, lp)
        val fadeDuration = FADE_OUT_DURATION_MS
        fadeOutThenPlayNext(streamHandle, shuffleEnabled, fadeDuration, alreadyLocked = true)
    }

    private fun refreshBoundarySync() {
        val h = handles ?: return
        val lp = loopPoint ?: return

        if (syncHandle != 0) {
            BASS.BASS_ChannelRemoveSync(h.stream, syncHandle)
            syncHandle = 0
        }
        syncProc = BASS.SYNCPROC { _, _, _, _ ->
            handlePlaybackBoundary(lp, h.stream)
        }

        val syncType: Int
        val syncParam: Long
        val syncLabel: String
        val tempEnd = temporaryEndPointMs
        if (tempEnd != null) {
            val totalBytes = BASS.BASS_ChannelGetLength(h.stream, BASS.BASS_POS_BYTE)
            val totalSecs = BASS.BASS_ChannelBytes2Seconds(h.stream, totalBytes)
            val totalMs = (totalSecs * 1000.0).toLong().coerceAtLeast(1L)
            val endMs = tempEnd.coerceIn(1L, totalMs)
            val endBytes = BASS.BASS_ChannelSeconds2Bytes(h.stream, endMs.toDouble() / 1000.0)
            syncType = BASS.BASS_SYNC_POS or BASS.BASS_SYNC_MIXTIME
            syncParam = endBytes
            syncLabel = "TEMP_END_MS"
        } else if (lp.hasLoopStartMarker) {
            val effectiveEndTick = getEffectiveLoopEndTick(h.stream, lp)
            val earlyEndTick = (effectiveEndTick - LOOP_SYNC_EARLY_TICKS).coerceAtLeast(lp.startTick.toLong().coerceAtLeast(1L))
            syncType = BASS.BASS_SYNC_POS or BASS.BASS_SYNC_MIXTIME or BASSMIDI.BASS_POS_MIDI_TICK
            syncParam = earlyEndTick
            syncLabel = "TICK_MIXTIME_EFFECTIVE_EARLY"
        } else {
            syncType = BASS.BASS_SYNC_END
            syncParam = 0L
            syncLabel = "END"
        }

        syncHandle = BASS.BASS_ChannelSetSync(
            h.stream,
            syncType,
            syncParam,
            syncProc,
            0
        )
        if (syncHandle == 0 && lp.hasLoopStartMarker) {
            // Fallback path if tick+mixtime sync is not accepted on this runtime.
            val bytes = BASS.BASS_ChannelGetLength(h.stream, BASS.BASS_POS_BYTE)
            syncHandle = BASS.BASS_ChannelSetSync(
                h.stream,
                BASS.BASS_SYNC_MIXTIME,
                bytes,
                syncProc,
                0
            )
        }
        if (LOOP_DIAG) {
            val lenTick = BASS.BASS_ChannelGetLength(h.stream, BASSMIDI.BASS_POS_MIDI_TICK)
            val lenBytes = BASS.BASS_ChannelGetLength(h.stream, BASS.BASS_POS_BYTE)
            val lenMs = (BASS.BASS_ChannelBytes2Seconds(h.stream, lenBytes) * 1000.0).toLong()
            val flags = BASS.BASS_ChannelFlags(h.stream, 0, 0)
            Log.d(
                LOOP_TAG,
                "sync set handle=$syncHandle type=$syncLabel param=$syncParam " +
                    "loopStartTick=${lp.startTick} loopEndTick=${lp.endTick} loopStartMs=${lp.startMs} loopEndMs=${lp.endMs} " +
                    "lenTick=$lenTick lenMs=$lenMs flags=$flags"
            )
        }
    }

    private fun getEffectiveLoopEndTick(streamHandle: Int, lp: LoopPoint): Long {
        val lenTick = BASS.BASS_ChannelGetLength(streamHandle, BASSMIDI.BASS_POS_MIDI_TICK)
        val lpEnd = lp.endTick.toLong().coerceAtLeast(1L)
        return if (lenTick > 0) minOf(lpEnd, lenTick) else lpEnd
    }

    private fun applyLoopRuntimeFlags() {
        val h = handles ?: return
        val lp = loopPoint
        BASS.BASS_ChannelFlags(h.stream, BASS.BASS_SAMPLE_LOOP, BASS.BASS_SAMPLE_LOOP)

        val enableDecay = lp?.hasLoopStartMarker == true
        val decayMask = BASSMIDI.BASS_MIDI_DECAYSEEK or BASSMIDI.BASS_MIDI_DECAYEND
        val decayFlags = if (enableDecay) decayMask else 0
        BASS.BASS_ChannelFlags(h.stream, decayFlags, decayMask)
        if (LOOP_DIAG) {
            Log.d(
                LOOP_TAG,
                "applyLoopRuntimeFlags loopEnabled=$loopEnabledSnapshot hasLoopMarker=${lp?.hasLoopStartMarker} autoSampleLoop=false " +
                    "decayEnabled=$enableDecay flags=${BASS.BASS_ChannelFlags(h.stream, 0, 0)}"
            )
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
            serviceScope.launch { playNextInQueue(shuffleEnabled, alreadyLocked = false) }
            return
        }

        volumeSlideSyncHandle = BASS.BASS_ChannelSetSync(
            streamHandle,
            BASS.BASS_SYNC_SLIDE,
            BASS.BASS_ATTRIB_VOL.toLong(),
            BASS.SYNCPROC { _, _, _, _ ->
                serviceScope.launch {
                    playNextInQueue(shuffleEnabled, alreadyLocked = true)
                }
            },
            0
        )
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
        if (!alreadyLocked && !transitionInProgress.compareAndSet(false, true)) return

        val nextUriString = findNextUriInActiveQueue(shuffleEnabled)
        if (nextUriString == null) {
            transitionInProgress.set(false)
            return
        }

        val loaded = loadMidi(nextUriString)
        if (loaded) {
            mainHandler.post {
                play()
                bassPlayer.invalidateFromBass()
                triggerNotificationUpdate()
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
        return BASS.BASS_Init(-1, 44100, 0)
    }

    private fun bassTerminate() {
        BASS.BASS_Free()
    }

    private fun bassLoadMidiWithSoundFont(midiPath: String, sf2Path: String): MidiHandles {
        val soundFontHandle = BASSMIDI.BASS_MIDI_FontInit(sf2Path, 0)
        val midiFlags = BASSMIDI.BASS_MIDI_NOCROP
        val stream = BASSMIDI.BASS_MIDI_StreamCreateFile(midiPath, 0, 0, 0, midiFlags)
        if (LOOP_DIAG) {
            Log.d(LOOP_TAG, "createStream flags=$midiFlags stream=$stream")
        }
        val fonts = arrayOf(
            BASSMIDI.BASS_MIDI_FONT().apply {
                font = soundFontHandle
                preset = -1
                bank = 0
            }
        )
        BASSMIDI.BASS_MIDI_StreamSetFonts(stream, fonts, 1)
        return MidiHandles(stream, soundFontHandle)
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

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val MIDI_FILE = "midi.mid"
        private const val SOUND_FONT_FILE = "soundfont.sf2"
        private const val LOOP_REPEAT_BEFORE_FADE_COUNT = 1
        private const val FADE_OUT_DURATION_MS = 8000

        private class SmfReader(private val bytes: ByteArray) {
            var pos: Int = 0
            fun canRead(n: Int): Boolean = pos + n <= bytes.size
            fun readU8(): Int = bytes[pos++].toInt() and 0xFF
            fun readU16(): Int = (readU8() shl 8) or readU8()
            fun readU32(): Int = (readU8() shl 24) or (readU8() shl 16) or (readU8() shl 8) or readU8()
            fun readAscii4(): String = String(bytes, pos, 4).also { pos += 4 }
            fun skip(n: Int) { pos = (pos + n).coerceAtMost(bytes.size) }
            fun readVarLen(): Int {
                var value = 0
                repeat(4) {
                    val b = readU8()
                    value = (value shl 7) or (b and 0x7F)
                    if ((b and 0x80) == 0) return value
                }
                return value
            }
        }

        private fun parseSmfMaxTick(bytes: ByteArray): Int? {
            val r = SmfReader(bytes)
            if (!r.canRead(14)) return null
            if (r.readAscii4() != "MThd") return null
            val headerLen = r.readU32()
            if (headerLen < 6 || !r.canRead(headerLen)) return null
            r.readU16() // format
            val trackCount = r.readU16()
            r.readU16() // division
            if (headerLen > 6) r.skip(headerLen - 6)
            if (trackCount <= 0) return null

            var maxTick = 0
            repeat(trackCount) {
                if (!r.canRead(8)) return@repeat
                val chunkType = r.readAscii4()
                val len = r.readU32()
                if (chunkType != "MTrk" || !r.canRead(len)) {
                    r.skip(len)
                    return@repeat
                }
                val trackEnd = r.pos + len
                var tick = 0
                var runningStatus = -1

                while (r.pos < trackEnd && r.canRead(1)) {
                    tick += r.readVarLen()
                    if (tick > maxTick) maxTick = tick
                    if (!r.canRead(1)) break
                    var status = r.readU8()
                    var firstData: Int? = null
                    if (status < 0x80) {
                        if (runningStatus < 0x80) break
                        firstData = status
                        status = runningStatus
                    } else if (status < 0xF0) {
                        runningStatus = status
                    }

                    when {
                        status == 0xFF -> {
                            if (!r.canRead(1)) break
                            r.readU8() // meta type
                            val metaLen = r.readVarLen()
                            if (!r.canRead(metaLen)) break
                            r.skip(metaLen)
                        }
                        status == 0xF0 || status == 0xF7 -> {
                            val syxLen = r.readVarLen()
                            if (!r.canRead(syxLen)) break
                            r.skip(syxLen)
                        }
                        status in 0x80..0xEF -> {
                            val eventType = status and 0xF0
                            val needTwo = eventType != 0xC0 && eventType != 0xD0
                            if (firstData == null) {
                                if (!r.canRead(1)) break
                                r.readU8()
                            }
                            if (needTwo) {
                                if (!r.canRead(1)) break
                                r.readU8()
                            }
                        }
                        else -> {
                            runningStatus = -1
                        }
                    }
                }
                if (r.pos < trackEnd) r.pos = trackEnd
            }
            return maxTick
        }

        private fun patchCachedMidiTailForBass(midiFile: File, targetEndTick: Int): Boolean {
            if (targetEndTick <= 1) return false
            val music = Midi1Music()
            val raw = midiFile.readBytes()
            music.read(raw.toList())
            if (music.tracks.isEmpty()) return false

            val track = selectTailPadTrack(music) ?: return false
            val safeChannel = findLeastUsedChannel(music)
            if (LOOP_DIAG) {
                val idx = music.tracks.indexOf(track)
                Log.d(LOOP_TAG, "tailPad trackIndex=$idx targetEndTick=$targetEndTick safeChannel=$safeChannel")
            }
            // Normalize EOT first so inserted events are never placed after an earlier EOT marker.
            stripEndOfTrackEvents(track.events)
            val padTick = (targetEndTick - 1).coerceAtLeast(0)
            // Use a harmless channel CC instead of All Sound Off (CC120), which can cut tails abruptly.
            insertChannelCcEvent(track.events, padTick, channel = safeChannel, ccNumber = 0, value = 0)
            ensureTrackEndOfTrackAtOrAfter(track.events, targetEndTick)

            val patched = writeMidiToBytes(music)
            midiFile.writeBytes(patched)
            return true
        }

        private fun selectTailPadTrack(music: Midi1Music): dev.atsushieno.ktmidi.Midi1Track? {
            var bestTrack: dev.atsushieno.ktmidi.Midi1Track? = null
            var bestTick = -1
            for (track in music.tracks) {
                var tick = 0
                var lastChannelTick = -1
                for (event in track.events) {
                    tick += event.deltaTime
                    val status = event.message.statusByte.toInt() and 0xFF
                    if (status in 0x80..0xEF) {
                        lastChannelTick = tick
                    }
                }
                if (lastChannelTick > bestTick) {
                    bestTick = lastChannelTick
                    bestTrack = track
                }
            }
            return bestTrack ?: music.tracks.firstOrNull { it.events.isNotEmpty() }
        }

        private fun stripEndOfTrackEvents(events: MutableList<Midi1Event>) {
            events.removeAll { isEndOfTrack(it) }
        }

        private fun findLeastUsedChannel(music: Midi1Music): Int {
            val counts = IntArray(16)
            for (track in music.tracks) {
                for (event in track.events) {
                    val status = event.message.statusByte.toInt() and 0xFF
                    if (status in 0x80..0xEF) {
                        val ch = status and 0x0F
                        counts[ch]++
                    }
                }
            }
            var bestChannel = 15
            var bestCount = Int.MAX_VALUE
            for (ch in 0 until 16) {
                if (counts[ch] < bestCount) {
                    bestCount = counts[ch]
                    bestChannel = ch
                }
            }
            return bestChannel
        }

        private fun insertChannelCcEvent(
            events: MutableList<Midi1Event>,
            targetTick: Int,
            channel: Int,
            ccNumber: Int,
            value: Int
        ) {
            var accumulatedTick = 0
            var insertIndex = events.size
            for ((index, event) in events.withIndex()) {
                if (accumulatedTick + event.deltaTime > targetTick) {
                    insertIndex = index
                    break
                }
                accumulatedTick += event.deltaTime
            }
            val deltaTime = (targetTick - accumulatedTick).coerceAtLeast(0)
            val statusByte = (MidiChannelStatus.CC or (channel and 0x0F))
            val message = Midi1SimpleMessage(statusByte, ccNumber, value)
            events.add(insertIndex, Midi1Event(deltaTime, message))
            if (insertIndex + 1 < events.size) {
                val nextEvent = events[insertIndex + 1]
                val newDelta = (nextEvent.deltaTime - deltaTime).coerceAtLeast(0)
                events[insertIndex + 1] = Midi1Event(newDelta, nextEvent.message)
            }
        }

        private fun ensureTrackEndOfTrackAtOrAfter(events: MutableList<Midi1Event>, targetTick: Int) {
            if (events.isEmpty()) {
                events.add(createEndOfTrackEvent(targetTick.coerceAtLeast(0)))
                return
            }
            var totalTick = 0
            for (event in events) totalTick += event.deltaTime
            val lastIndex = events.lastIndex
            val last = events[lastIndex]
            if (isEndOfTrack(last)) {
                if (totalTick < targetTick) {
                    events[lastIndex] = Midi1Event(last.deltaTime + (targetTick - totalTick), last.message)
                }
            } else if (totalTick <= targetTick) {
                events.add(createEndOfTrackEvent(targetTick - totalTick))
            } else {
                events.add(createEndOfTrackEvent(0))
            }
        }

        fun findLoopPoint(midiFile: File): LoopPoint {
            val loopPoint = LoopPoint()
            try {
                midiFile.inputStream().use { inputStream ->
                    val rawBytes = inputStream.readBytes()
                    val bytes = rawBytes.toList()
                    val music = Midi1Music().apply { read(bytes) }

                    var maxTickFromMusic = 0
                    for (track in music.tracks) {
                        var tick = 0
                        for (e in track.events) {
                            tick += e.deltaTime
                            val m = e.message
                            val isCC = ((m.statusByte.toInt() and 0xF0) == MidiChannelStatus.CC)
                            if (isCC && m.msb.toInt() == 111) {
                                if (!loopPoint.hasLoopStartMarker || tick < loopPoint.startTick) {
                                    loopPoint.hasLoopStartMarker = true
                                    loopPoint.startTick = tick
                                    loopPoint.startMs = music.getTimePositionInMillisecondsForTick(tick).toLong()
                                }
                            }
                        }
                        if (tick > maxTickFromMusic) maxTickFromMusic = tick
                    }
                    val maxTickSmf = parseSmfMaxTick(rawBytes)
                    val maxTick = maxTickSmf ?: maxTickFromMusic
                    loopPoint.endTick = maxTick
                    loopPoint.endMs = music.getTimePositionInMillisecondsForTick(maxTick).toLong()
                    if (LOOP_DIAG) {
                        Log.d(
                            LOOP_TAG,
                            "findLoopPoint maxTickSmf=$maxTickSmf maxTickMusic=$maxTickFromMusic " +
                                "chosenEndTick=${loopPoint.endTick} chosenEndMs=${loopPoint.endMs}"
                        )
                    }
                }
            } catch (_: Exception) {
                // Parse errors should not crash playback. Use default loop values.
            }
            return loopPoint
        }

        private const val LOOP_TAG = "LoopDiag"
        private const val LOOP_DIAG = true
        private const val LOOP_TAIL_PADDING_EXPERIMENT = true
        private const val LOOP_SYNC_EARLY_TICKS = 96L
    }

    private fun playInternalFromController() {
        if (!requestAudioFocus()) {
            bassPlayer.invalidateFromBass()
            return
        }
        registerNoisyReceiver()
        handles?.let { BASS.BASS_ChannelPlay(it.stream, false) }
        setSessionPositionAnchor(readRawPositionMs())
        bassPlayer.invalidateFromBass()
    }

    private fun pauseInternalFromController(releaseFocus: Boolean) {
        setSessionPositionAnchor(getSessionPositionMs())
        handles?.let { BASS.BASS_ChannelPause(it.stream) }
        unregisterNoisyReceiver()
        if (releaseFocus) abandonAudioFocus()
        bassPlayer.invalidateFromBass()
    }

    private fun seekInternalFromController(ms: Long) {
        setCurrentPositionMs(ms)
        bassPlayer.invalidateFromBass()
    }

    private fun notifyLooped(startMs: Long) {
        val now = SystemClock.uptimeMillis()
        sessionLastLoopUptimeMs = now
        setSessionPositionAnchor(startMs, now)
        if (LOOP_DIAG) {
            Log.d(LOOP_TAG, "notifyLooped anchor=$startMs raw=${readRawPositionMs()}")
        }

        mainHandler.post {
            bassPlayer.notifyLoopDiscontinuity(startMs)
            bassPlayer.invalidateFromBass()
            triggerNotificationUpdate()
        }
    }

    private fun setSessionPositionAnchor(positionMs: Long, uptimeMs: Long = SystemClock.uptimeMillis()) {
        sessionPositionAnchorMs = positionMs.coerceAtLeast(0L)
        sessionAnchorUptimeMs = uptimeMs
        sessionAnchorValid = true
    }

    private fun getActiveLoopWindowMs(): Pair<Long, Long>? {
        val tempEnd = temporaryEndPointMs
        if (tempEnd != null && tempEnd > 0L) {
            val start = (temporaryLoopPointMs ?: loopPoint?.startMs ?: 0L).coerceIn(0L, tempEnd - 1L)
            val end = tempEnd.coerceAtLeast(start + 1L)
            return start to end
        }

        if (!loopEnabledSnapshot) return null
        val lp = loopPoint ?: return null
        if (!lp.hasLoopStartMarker) return null
        if (lp.endMs <= lp.startMs) return null
        return lp.startMs to lp.endMs
    }

    private fun wrapPositionInLoopWindow(positionMs: Long, startMs: Long, endMs: Long): Long {
        val length = (endMs - startMs).coerceAtLeast(1L)
        if (positionMs < startMs) return startMs
        val delta = (positionMs - startMs) % length
        return startMs + delta
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
    val stream: Int,
    val font: Int
)
