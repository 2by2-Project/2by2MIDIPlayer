package jp.project2by2.musicplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.un4seen.bass.BASS
import com.un4seen.bass.BASSMIDI
import java.io.File

data class PreviewWindow(
    val loopStartMs: Long?,
    val endMs: Long?
)

class LoopPreviewPlayer(
    private val context: Context
) {
    private var handles: MidiHandles? = null
    private var tempMidiFile: File? = null
    private var bassAcquired = false
    private var previewWindow: PreviewWindow? = null

    @Synchronized fun load(uri: Uri, previewWindow: PreviewWindow? = null): Boolean {
        val soundFontFile = File(context.cacheDir, "soundfont.sf2")
        if (!soundFontFile.exists()) return false
        if (!bassAcquired) {
            bassAcquired = BassRuntime.acquire()
            if (!bassAcquired) return false
        }

        releaseHandles()

        val midiFile = resolveMidiFile(uri) ?: return false
        val loadedHandles = loadStream(midiFile, soundFontFile) ?: return false
        handles = loadedHandles
        this.previewWindow = previewWindow
        setPreviewWindow(previewWindow?.loopStartMs, previewWindow?.endMs)
        return true
    }

    @Synchronized fun setPreviewWindow(loopStartMs: Long?, endMs: Long?) {
        previewWindow = PreviewWindow(loopStartMs = loopStartMs, endMs = endMs)
        refreshBoundarySync()
    }

    @Synchronized fun play(): Boolean {
        val h = handles ?: return false
        if (h.audio.positionMs() >= h.audio.durationMs) h.audio.seek(0)
        return BASS.BASS_ChannelPlay(h.stream, false)
    }

    @Synchronized fun pause() {
        handles?.let { BASS.BASS_ChannelPause(it.stream) }
    }

    @Synchronized fun seekTo(ms: Long) {
        handles?.audio?.seek(ms)
    }

    @Synchronized fun getCurrentPositionMs(): Long {
        return handles?.audio?.positionMs() ?: 0L
    }

    @Synchronized fun getDurationMs(): Long {
        return handles?.audio?.durationMs ?: 0L
    }

    @Synchronized fun isPlaying(): Boolean {
        val stream = handles?.stream ?: return false
        return BASS.BASS_ChannelIsActive(stream) == BASS.BASS_ACTIVE_PLAYING
    }

    @Synchronized fun release() {
        releaseHandles()
        tempMidiFile?.delete()
        tempMidiFile = null
        if (bassAcquired) {
            BassRuntime.release()
            bassAcquired = false
        }
    }

    private fun resolveMidiFile(uri: Uri): File? {
        if (uri.scheme == "file") {
            tempMidiFile = null
            return uri.path?.let(::File)
        }
        val target = File(context.cacheDir, "loop_preview_${uri.toString().hashCode().toUInt().toString(16)}.mid")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            tempMidiFile = target
            target
        }.getOrElse {
            Log.e("LoopPreviewPlayer", "Failed to copy MIDI for preview: $uri", it)
            null
        }
    }

    private fun loadStream(midiFile: File, soundFontFile: File): MidiHandles? {
        val font = BASSMIDI.BASS_MIDI_FontInit(soundFontFile.absolutePath, 0)
        if (font == 0) return null
        return try {
            MidiHandles(createAndroidLoopStream(midiFile.absolutePath, font), font)
        } catch (error: Exception) {
            BASSMIDI.BASS_MIDI_FontFree(font)
            Log.e("LoopPreviewPlayer", "Cannot load MIDI", error)
            null
        }
    }

    private fun refreshBoundarySync() {
        val audio = handles?.audio ?: return
        val end = (previewWindow?.endMs ?: audio.durationMs).coerceIn(0L, audio.durationMs)
        val start = previewWindow?.loopStartMs?.coerceIn(0L, (end - 1L).coerceAtLeast(0L))
        val repeat = start != null && previewWindow?.endMs != null
        audio.configure(start ?: 0, end) { repeat }
    }

    private fun releaseHandles() {
        handles?.let {
            it.audio.close()
            BASSMIDI.BASS_MIDI_FontFree(it.font)
        }
        handles = null
    }
}
