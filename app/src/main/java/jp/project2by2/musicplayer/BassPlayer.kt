package jp.project2by2.musicplayer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class BassPlayer(
    looper: Looper = Looper.getMainLooper(),
    private val initialTitle: String,
    initialArtworkUri: Uri? = null,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onSeekToPrevious: () -> Unit,
    private val onSeekToNext: () -> Unit,
    private val onSetLoopEnabled: (Boolean) -> Unit,
    private val onSetShuffleEnabled: (Boolean) -> Unit,
    private val queryPositionMs: () -> Long,
    private val queryDurationMs: () -> Long,
    private val queryIsPlaying: () -> Boolean,
    private val queryLoopEnabled: () -> Boolean,
    private val queryShuffleEnabled: () -> Boolean,
) : SimpleBasePlayer(looper) {
    private companion object {
        private const val MAX_REASONABLE_DURATION_MS = 7L * 24L * 60L * 60L * 1000L
    }

    private val playerHandler = Handler(looper)
    private val applicationLooper = looper

    private var title: String = initialTitle
    private var artist: String? = null
    private var artworkUri: Uri? = initialArtworkUri
    private var pendingDiscontinuityPositionMs: Long? = null

    private val mediaId = "midi"

    fun setMetadata(title: String, artist: String?, artworkUri: Uri?) {
        val update = {
            this.title = title
            this.artist = artist
            this.artworkUri = artworkUri
            invalidateState()
        }
        if (Looper.myLooper() == applicationLooper) {
            update()
        } else {
            playerHandler.post(update)
        }
    }

    fun invalidateFromBass() {
        val update = {
            invalidateState()
        }
        if (Looper.myLooper() == applicationLooper) {
            update()
        } else {
            playerHandler.post(update)
        }
    }

    fun notifyLoopDiscontinuity(positionMs: Long) {
        val update = {
            pendingDiscontinuityPositionMs = positionMs
        }
        if (Looper.myLooper() == applicationLooper) {
            update()
        } else {
            playerHandler.post(update)
        }
    }

    override fun getState(): State {
        val isPlaying = queryIsPlaying()
        val posMs = queryPositionMs()
        val durMs = queryDurationMs()

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(artworkUri)
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()

        val itemDataBuilder = MediaItemData.Builder(mediaId)
            .setMediaItem(mediaItem)
            .setMediaMetadata(metadata)
            .setIsSeekable(durMs > 0)
        val safeDurationUs = durMs
            .takeIf { it in 1L..MAX_REASONABLE_DURATION_MS }
            ?.times(1000L)
        if (safeDurationUs != null && safeDurationUs > 0L) {
            itemDataBuilder.setDurationUs(safeDurationUs)
        }
        val itemData = itemDataBuilder.build()

        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SET_REPEAT_MODE)
            .add(Player.COMMAND_SET_SHUFFLE_MODE)
            .build()

        val pendingDiscontinuity = pendingDiscontinuityPositionMs
        pendingDiscontinuityPositionMs = null

        val stateBuilder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(itemData))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(if (queryLoopEnabled()) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(queryShuffleEnabled())
            .setContentPositionMs(posMs)
        if (pendingDiscontinuity != null) {
            stateBuilder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, pendingDiscontinuity)
        }
        return stateBuilder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) onPlay() else onPause()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_WINDOW -> onSeekToPrevious()
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT_WINDOW -> onSeekToNext()
            else -> onSeek(positionMs)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        onSetLoopEnabled(repeatMode != Player.REPEAT_MODE_OFF)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        onSetShuffleEnabled(shuffleModeEnabled)
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
