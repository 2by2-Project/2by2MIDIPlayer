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
    private var syncProc: BASS.SYNCPROC? = null
    private var syncHandle: Int = 0
    private var tempMidiFile: File? = null
    private var bassAcquired = false
    private var previewWindow: PreviewWindow? = null

    fun load(uri: Uri, previewWindow: PreviewWindow? = null): Boolean {
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

    fun setPreviewWindow(loopStartMs: Long?, endMs: Long?) {
        previewWindow = PreviewWindow(loopStartMs = loopStartMs, endMs = endMs)
        refreshBoundarySync()
    }

    fun play(): Boolean {
        val stream = handles?.stream ?: return false
        return BASS.BASS_ChannelPlay(stream, false)
    }

    fun pause() {
        handles?.let { BASS.BASS_ChannelPause(it.stream) }
    }

    fun seekTo(ms: Long) {
        val stream = handles?.stream ?: return
        val durationMs = getDurationMs().coerceAtLeast(0L)
        val clamped = ms.coerceIn(0L, durationMs)
        val bytes = BASS.BASS_ChannelSeconds2Bytes(stream, clamped.toDouble() / 1000.0)
        BASS.BASS_ChannelSetPosition(stream, bytes, BASS.BASS_POS_BYTE)
    }

    fun getCurrentPositionMs(): Long {
        val stream = handles?.stream ?: return 0L
        val bytes = BASS.BASS_ChannelGetPosition(stream, BASS.BASS_POS_BYTE)
        return (BASS.BASS_ChannelBytes2Seconds(stream, bytes) * 1000.0).toLong()
    }

    fun getDurationMs(): Long {
        val stream = handles?.stream ?: return 0L
        val bytes = BASS.BASS_ChannelGetLength(stream, BASS.BASS_POS_BYTE)
        return (BASS.BASS_ChannelBytes2Seconds(stream, bytes) * 1000.0).toLong()
    }

    fun isPlaying(): Boolean {
        val stream = handles?.stream ?: return false
        return BASS.BASS_ChannelIsActive(stream) == BASS.BASS_ACTIVE_PLAYING
    }

    fun release() {
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
        val soundFontHandle = BASSMIDI.BASS_MIDI_FontInit(soundFontFile.absolutePath, 0)
        if (soundFontHandle == 0) return null
        val stream = BASSMIDI.BASS_MIDI_StreamCreateFile(
            midiFile.absolutePath,
            0,
            0,
            0,
            BASSMIDI.BASS_MIDI_NOCROP
        )
        if (stream == 0) {
            BASSMIDI.BASS_MIDI_FontFree(soundFontHandle)
            return null
        }
        val fonts = arrayOf(
            BASSMIDI.BASS_MIDI_FONT().apply {
                font = soundFontHandle
                preset = -1
                bank = 0
            }
        )
        BASSMIDI.BASS_MIDI_StreamSetFonts(stream, fonts, 1)
        BASSMIDI.BASS_MIDI_StreamLoadSamples(stream)
        BASS.BASS_ChannelSetAttribute(stream, BASS.BASS_ATTRIB_VOL, 1f)
        return MidiHandles(stream = stream, font = soundFontHandle)
    }

    private fun refreshBoundarySync() {
        val stream = handles?.stream ?: return
        if (syncHandle != 0) {
            BASS.BASS_ChannelRemoveSync(stream, syncHandle)
            syncHandle = 0
        }
        val endMs = previewWindow?.endMs ?: return
        val durationMs = getDurationMs().coerceAtLeast(1L)
        val clampedEndMs = endMs.coerceIn(1L, durationMs)
        val endBytes = BASS.BASS_ChannelSeconds2Bytes(stream, clampedEndMs.toDouble() / 1000.0)
        syncProc = BASS.SYNCPROC { _, _, _, _ ->
            handleBoundaryReached(stream, clampedEndMs)
        }
        syncHandle = BASS.BASS_ChannelSetSync(
            stream,
            BASS.BASS_SYNC_POS or BASS.BASS_SYNC_MIXTIME,
            endBytes,
            syncProc,
            0
        )
    }

    private fun handleBoundaryReached(stream: Int, clampedEndMs: Long) {
        if (handles?.stream != stream) return
        val loopStartMs = previewWindow?.loopStartMs?.coerceIn(0L, (clampedEndMs - 1L).coerceAtLeast(0L))
        if (loopStartMs != null) {
            seekTo(loopStartMs)
        } else {
            pause()
            seekTo(clampedEndMs)
        }
    }

    private fun releaseHandles() {
        handles?.let {
            if (syncHandle != 0) {
                BASS.BASS_ChannelRemoveSync(it.stream, syncHandle)
                syncHandle = 0
            }
            BASS.BASS_StreamFree(it.stream)
            BASSMIDI.BASS_MIDI_FontFree(it.font)
        }
        syncProc = null
        handles = null
    }
}
