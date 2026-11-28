@file:OptIn(ExperimentalForeignApi::class)

package jp.project2by2.musicplayer.ios.audio

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.*
import platform.Foundation.NSData
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.MediaPlayer.*
import platform.UIKit.UIImage
import platform.darwin.NSObjectProtocol

/**
 * AVAudioSession for background playback, with Android's audio-focus behavior: an interruption
 * (call, alarm, Siri) pauses and resumes afterwards when the system allows it, and unplugging
 * headphones or disconnecting Bluetooth pauses ("becoming noisy").
 */
class AudioSessionController(
    private val onInterruptionBegan: () -> Unit,
    private val onInterruptionEnded: (shouldResume: Boolean) -> Unit,
    private val onOutputLost: () -> Unit,
    private val onRouteChanged: () -> Unit,
) {
    private val session = AVAudioSession.sharedInstance()
    private val observers = mutableListOf<NSObjectProtocol>()

    fun start() {
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(AVAudioSessionInterruptionNotification, `object` = session,
            queue = NSOperationQueue.mainQueue) { note -> handleInterruption(note) }
        observers += center.addObserverForName(AVAudioSessionRouteChangeNotification, `object` = session,
            queue = NSOperationQueue.mainQueue) { note ->
            val reason = (note?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) onOutputLost()
            onRouteChanged()
        }
        observers += center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, `object` = session,
            queue = NSOperationQueue.mainQueue) { _ ->
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        }
    }

    /** Before playing: another app may have taken the session while this one was paused. */
    fun activate() {
        session.setActive(true, error = null)
    }

    private fun handleInterruption(note: NSNotification?) {
        val info = note?.userInfo ?: return
        val type = (info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue ?: return
        if (type == AVAudioSessionInterruptionTypeBegan) {
            onInterruptionBegan()
        } else {
            val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0u
            session.setActive(true, error = null)
            onInterruptionEnded(options and AVAudioSessionInterruptionOptionShouldResume != 0uL)
        }
    }

    /**
     * Delay between the engine position and what is heard. Bluetooth routes report their larger
     * latency here (Android reads the A2DP delay); wired and built-in outputs add a few ms.
     */
    fun outputDelayMs(): Int = ((session.outputLatency + session.IOBufferDuration) * 1000).toInt().coerceIn(0, 1500)

    fun stop() {
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
    }
}

/** What the lock screen, Control Center, CarPlay and headphones show and control. */
data class NowPlayingInfo(
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val artwork: NSData?,
)

/** Lock screen, Control Center and headphone commands: play/pause, previous/next, seeking, loop and shuffle. */
class NowPlayingCenter(
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onToggle: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onLoop: (Boolean) -> Unit,
    private val onShuffle: (Boolean) -> Unit,
) {
    private val commands = MPRemoteCommandCenter.sharedCommandCenter()
    private var artworkSource: NSData? = null
    private var artwork: MPMediaItemArtwork? = null

    fun start() {
        commands.playCommand.addTargetWithHandler { _ -> onPlay(); MPRemoteCommandHandlerStatusSuccess }
        commands.pauseCommand.addTargetWithHandler { _ -> onPause(); MPRemoteCommandHandlerStatusSuccess }
        commands.togglePlayPauseCommand.addTargetWithHandler { _ -> onToggle(); MPRemoteCommandHandlerStatusSuccess }
        commands.nextTrackCommand.addTargetWithHandler { _ -> onNext(); MPRemoteCommandHandlerStatusSuccess }
        commands.previousTrackCommand.addTargetWithHandler { _ -> onPrevious(); MPRemoteCommandHandlerStatusSuccess }
        commands.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val time = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
            if (time == null) MPRemoteCommandHandlerStatusCommandFailed
            else { onSeek((time * 1000).toLong()); MPRemoteCommandHandlerStatusSuccess }
        }
        commands.changeRepeatModeCommand.addTargetWithHandler { event ->
            val type = (event as? MPChangeRepeatModeCommandEvent)?.repeatType ?: MPRepeatType.MPRepeatTypeOff
            onLoop(type != MPRepeatType.MPRepeatTypeOff)
            MPRemoteCommandHandlerStatusSuccess
        }
        commands.changeShuffleModeCommand.addTargetWithHandler { event ->
            val type = (event as? MPChangeShuffleModeCommandEvent)?.shuffleType ?: MPShuffleType.MPShuffleTypeOff
            onShuffle(type != MPShuffleType.MPShuffleTypeOff)
            MPRemoteCommandHandlerStatusSuccess
        }
        commands.skipForwardCommand.enabled = false
        commands.skipBackwardCommand.enabled = false
    }

    fun update(info: NowPlayingInfo?, loop: Boolean, shuffle: Boolean) {
        commands.changeRepeatModeCommand.currentRepeatType = if (loop) MPRepeatType.MPRepeatTypeAll else MPRepeatType.MPRepeatTypeOff
        commands.changeShuffleModeCommand.currentShuffleType = if (shuffle) MPShuffleType.MPShuffleTypeItems else MPShuffleType.MPShuffleTypeOff
        val center = MPNowPlayingInfoCenter.defaultCenter()
        if (info == null) {
            center.nowPlayingInfo = null
            return
        }
        if (info.artwork !== artworkSource) {
            artworkSource = info.artwork
            artwork = info.artwork?.let { UIImage.imageWithData(it) }?.let { image ->
                MPMediaItemArtwork(boundsSize = image.size, requestHandler = { _ -> image })
            }
        }
        val values = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to info.title,
            MPMediaItemPropertyPlaybackDuration to info.durationMs / 1000.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to info.positionMs / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to if (info.playing) 1.0 else 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate to 1.0,
        )
        info.artist?.takeIf { it.isNotBlank() }?.let { values[MPMediaItemPropertyArtist] = it }
        artwork?.let { values[MPMediaItemPropertyArtwork] = it }
        center.nowPlayingInfo = values
    }
}
