package jp.project2by2.musicplayer.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class NativePlatform(val directory: String, val core: String, val midi: String) {
    Windows("win-x64", "bass.dll", "bassmidi.dll"),
    Linux("linux-x64", "libbass.so", "libbassmidi.so");

    companion object {
        fun detect(os: String = System.getProperty("os.name"), arch: String = System.getProperty("os.arch")): NativePlatform {
            require(arch.lowercase() in listOf("amd64", "x86_64")) { "対応するCPUはWindows/Linux x64です: $arch" }
            return when {
                os.startsWith("Windows", true) -> Windows
                os.startsWith("Linux", true) -> Linux
                else -> error("対応するOSはWindows/Linuxです: $os")
            }
        }
    }
}

internal interface BassCore : Library {
    fun BASS_GetVersion(): Int
    fun BASS_Init(device: Int, freq: Int, flags: Int, window: Pointer?, clsid: Pointer?): Boolean
    fun BASS_Free(): Boolean
    fun BASS_ErrorGetCode(): Int
    fun BASS_StreamFree(handle: Int): Boolean
    fun BASS_ChannelPlay(handle: Int, restart: Boolean): Boolean
    fun BASS_ChannelPause(handle: Int): Boolean
    fun BASS_ChannelGetPosition(handle: Int, mode: Int): Long
    fun BASS_ChannelGetLength(handle: Int, mode: Int): Long
    fun BASS_ChannelBytes2Seconds(handle: Int, bytes: Long): Double
    fun BASS_ChannelSeconds2Bytes(handle: Int, seconds: Double): Long
    fun BASS_ChannelSetPosition(handle: Int, position: Long, mode: Int): Boolean
    fun BASS_ChannelIsActive(handle: Int): Int
    fun BASS_ChannelSetAttribute(handle: Int, attribute: Int, value: Float): Boolean
    fun BASS_ChannelFlags(handle: Int, flags: Int, mask: Int): Int
    fun BASS_ChannelGetData(handle: Int, buffer: Pointer, length: Int): Int
    fun BASS_ChannelSetSync(handle: Int, type: Int, parameter: Long, callback: EndSync, user: Pointer?): Int
}

internal fun interface EndSync : Callback {
    fun invoke(sync: Int, channel: Int, data: Int, user: Pointer?)
}

internal interface BassMidi : Library {
    fun BASS_MIDI_GetVersion(): Int
    fun BASS_MIDI_StreamCreateFile(fileType: Int, file: Pointer, offset: Long, length: Long, flags: Int, frequency: Int): Int
    fun BASS_MIDI_FontInit(file: Pointer, flags: Int): Int
    fun BASS_MIDI_FontFree(font: Int): Boolean
    fun BASS_MIDI_StreamSetFonts(stream: Int, fonts: Pointer, count: Int): Boolean
}

data class AudioPosition(val positionMs: Long = 0, val durationMs: Long = 0, val playing: Boolean = false)

/** Owns one BASS device, stream and font. All UI calls run off the UI thread. */
class BassAudio(private val device: Int = -1) : AutoCloseable {
    private var core: BassCore? = null
    private var midi: BassMidi? = null
    private var stream = 0
    private var font = 0
    private var endSync: EndSync? = null // Keep the native callback alive until StreamFree returns.
    private val looping = AtomicBoolean(false)
    private var maxVoices = 40
    private var effectsEnabled = false
    private var reverbStrength = 1f
    private val platform = NativePlatform.detect()
    private val unicodeFlag get() = if (platform == NativePlatform.Windows) Int.MIN_VALUE else 0

    @Synchronized
    fun initialize(): String {
        core?.let { return "BASS ${version(it.BASS_GetVersion())} · BASSMIDI ${version(midi!!.BASS_MIDI_GetVersion())}" }
        val base = System.getProperty("bass.native.dir")?.let(::File)
            ?: System.getProperty("compose.application.resources.dir")?.let { File(it, "proprietary") }
            ?: File("proprietary")
        val folder = File(base, platform.directory)
        listOf(platform.core, platform.midi).forEach {
            require(File(folder, it).isFile) { "音源ライブラリがありません: ${File(folder, it).absolutePath}" }
        }
        // Both supported targets use the unified x64 calling convention. Load BASS first:
        // BASSMIDI imports it, and on Linux resolves its SONAME from the loaded library.
        val loadedCore = Native.load(File(folder, platform.core).absolutePath, BassCore::class.java)
        val loadedMidi = Native.load(File(folder, platform.midi).absolutePath, BassMidi::class.java)
        check(loadedCore.BASS_GetVersion() ushr 16 == 0x0204 && loadedMidi.BASS_MIDI_GetVersion() ushr 16 == 0x0204) { "BASS/BASSMIDI 2.4が必要です" }
        check(loadedCore.BASS_Init(device, 44100, 0, null, null)) { "音声デバイスを初期化できません (BASS ${loadedCore.BASS_ErrorGetCode()})" }
        core = loadedCore
        midi = loadedMidi
        return initialize()
    }

    @Synchronized
    fun setSoundFont(file: File) {
        initialize()
        val api = midi!!
        val next = pathMemory(file).use { api.BASS_MIDI_FontInit(it, unicodeFlag) }
        check(next != 0) { failure("SoundFontを読み込めません") }
        try {
            if (stream != 0) attachFont(stream, next)
        } catch (e: Exception) {
            api.BASS_MIDI_FontFree(next)
            throw e
        }
        val previous = font
        font = next
        if (previous != 0) api.BASS_MIDI_FontFree(previous)
    }

    private fun attachFont(target: Int, soundFont: Int) {
        // BASS_MIDI_FONT: DWORD font, int preset, int bank (three 32-bit fields).
        Memory(12).use {
            it.setInt(0, soundFont); it.setInt(4, -1); it.setInt(8, 0)
            check(midi!!.BASS_MIDI_StreamSetFonts(target, it, 1)) { failure("SoundFontを割り当てられません") }
        }
    }

    @Synchronized
    fun load(file: File, loopStartTick: Int?, volume: Float) {
        initialize()
        check(font != 0) { "設定からSoundFont (.sf2 / .sf3 / .sfz) を選択してください" }
        val bass = core!!
        val next = pathMemory(file).use {
            midi!!.BASS_MIDI_StreamCreateFile(0, it, 0, 0, unicodeFlag or 0x8000, 44100)
        }
        check(next != 0) { failure("MIDIを開けません") }
        val nextSync = EndSync { _, channel, _, _ ->
            if (looping.get()) bass.BASS_ChannelSetPosition(channel, (loopStartTick ?: 0).toLong(), 2)
        }
        try {
            attachFont(next, font)
            applySynthSettings(next)
            check(bass.BASS_ChannelSetAttribute(next, 2, volume.coerceIn(0f, 1f))) { failure("音量を設定できません") }
            check(bass.BASS_ChannelSetSync(next, 2 or 0x40000000, 0, nextSync, null) != 0) { failure("ループを設定できません") }
        } catch (e: Exception) {
            bass.BASS_StreamFree(next)
            throw e
        }
        if (stream != 0) bass.BASS_StreamFree(stream)
        stream = next
        endSync = nextSync
    }

    @Synchronized fun play() {
        check(stream != 0) { "MIDIファイルを選択してください" }
        val position = position()
        if (position.positionMs >= position.durationMs - 20) seek(0)
        check(core!!.BASS_ChannelPlay(stream, false)) { failure("再生できません") }
    }
    @Synchronized fun pause() { if (stream != 0) check(core!!.BASS_ChannelPause(stream)) { failure("一時停止できません") } }
    @Synchronized fun seek(ms: Long) {
        if (stream != 0) {
            val pos = core!!.BASS_ChannelSeconds2Bytes(stream, ms.coerceAtLeast(0) / 1000.0)
            check(core!!.BASS_ChannelSetPosition(stream, pos, 0)) { failure("シークできません") }
        }
    }
    @Synchronized fun volume(value: Float) {
        if (stream != 0) check(core!!.BASS_ChannelSetAttribute(stream, 2, value.coerceIn(0f, 1f))) { failure("音量を設定できません") }
    }
    fun loop(value: Boolean) { looping.set(value) }
    @Synchronized fun synthSettings(voices: Int, effects: Boolean, reverb: Float) {
        maxVoices = voices.coerceIn(1, 1000)
        effectsEnabled = effects
        reverbStrength = reverb.coerceIn(0f, 3f)
        if (stream != 0) applySynthSettings(stream)
    }
    private fun applySynthSettings(handle: Int) {
        val bass = core!!
        check(bass.BASS_ChannelSetAttribute(handle, 0x12003, maxVoices.toFloat())) { failure("最大発音数を設定できません") }
        check(bass.BASS_ChannelSetAttribute(handle, 0x12009, reverbStrength)) { failure("リバーブを設定できません") }
        check(bass.BASS_ChannelFlags(handle, if (effectsEnabled) 0 else 0x2000, 0x2000) != -1) { failure("エフェクトを設定できません") }
    }
    @Synchronized fun unload() {
        if (stream != 0) core?.BASS_StreamFree(stream)
        stream = 0; endSync = null
    }
    @Synchronized fun position(): AudioPosition {
        if (stream == 0) return AudioPosition()
        val bass = core!!
        fun millis(bytes: Long) = if (bytes < 0) 0L else (bass.BASS_ChannelBytes2Seconds(stream, bytes) * 1000).toLong()
        return AudioPosition(millis(bass.BASS_ChannelGetPosition(stream, 0)), millis(bass.BASS_ChannelGetLength(stream, 0)), bass.BASS_ChannelIsActive(stream) == 1)
    }
    @Synchronized override fun close() {
        looping.set(false)
        if (stream != 0) core?.BASS_StreamFree(stream)
        stream = 0; endSync = null
        if (font != 0) midi?.BASS_MIDI_FontFree(font)
        font = 0
        core?.BASS_Free()
        core = null; midi = null
    }
    private fun failure(message: String) = "$message (BASS ${core?.BASS_ErrorGetCode()})"
    private fun pathMemory(file: File): Memory {
        val bytes = if (platform == NativePlatform.Windows) (file.absolutePath + '\u0000').toByteArray(Charsets.UTF_16LE)
            else (file.absolutePath + '\u0000').toByteArray(Charsets.UTF_8)
        return Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
    }
    private fun version(number: Int) = listOf(24, 16, 8, 0).joinToString(".") { ((number ushr it) and 255).toString() }
}
