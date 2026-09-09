package jp.project2by2.musicplayer.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import jp.project2by2.musicplayer.soundfont.SoundFontFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Decode the converted bank through shipped BASSMIDI on either Windows or Linux; no audio output. */
class NativeDlsTest {
    @Test fun convertedTemporarySf2ProducesAudio() {
        val platform = NativePlatform.detect()
        val native = File(System.getProperty("bass.native.dir"), platform.directory)
        val bass = Native.load(File(native, platform.core).absolutePath, BassCore::class.java)
        val midi = Native.load(File(native, platform.midi).absolutePath, BassMidi::class.java)
        assertTrue(bass.BASS_Init(0, 44100, 0, null, null))
        val dir = Files.createTempDirectory("DLS-音源-").toFile()
        var font = 0; var stream = 0
        val unicode = if (platform == NativePlatform.Windows) Int.MIN_VALUE else 0
        fun path(file: File): Memory {
            val data = (file.absolutePath + '\u0000').toByteArray(if (unicode != 0) Charsets.UTF_16LE else Charsets.UTF_8)
            return Memory(data.size.toLong()).apply { write(0, data, 0, data.size) }
        }
        try {
            val input = File(dir, "音源.dls").apply { writeBytes(testDls(percussion = false)) }
            val song = File(dir, "音楽.mid").apply {
                writeBytes(byteArrayOf(77,84,104,100,0,0,0,6,0,0,0,1,1,0xE0.toByte(),77,84,114,107,0,0,0,13,
                    0,0x90.toByte(),60,100,0x83.toByte(),0x60,0x80.toByte(),60,0,0,0xFF.toByte(),0x2F,0))
            }
            val inputs = mutableListOf(input)
            // Optional real-world collection already installed by Windows, never redistributed.
            if (platform == NativePlatform.Windows) File(System.getenv("SystemRoot"), "System32/drivers/gm.dls")
                .takeIf { it.isFile }?.let(inputs::add)
            for (source in inputs) {
                val started = System.nanoTime()
                SoundFontFiles.prepare(source, dir).use { prepared ->
                    println("${source.name}: DLS -> SF2 ${(System.nanoTime() - started) / 1_000_000}ms, ${prepared.file.length()} bytes")
                    try {
                        font = path(prepared.file).use { midi.BASS_MIDI_FontInit(it, unicode) }
                        assertNotEquals(0, font, "FontInit: ${bass.BASS_ErrorGetCode()}")
                        stream = path(song).use { midi.BASS_MIDI_StreamCreateFile(0, it, 0, 0, unicode or 0x200000, 44100) }
                        assertNotEquals(0, stream)
                        Memory(12).use { config ->
                            config.setInt(0, font); config.setInt(4, -1); config.setInt(8, 0)
                            assertTrue(midi.BASS_MIDI_StreamSetFonts(stream, config, 1))
                        }
                        Memory(32768).use { buffer ->
                            val count = bass.BASS_ChannelGetData(stream, buffer, 32768)
                            assertTrue(count > 0)
                            assertTrue(buffer.getByteArray(0, count).any { it.toInt() != 0 }, "Converted ${source.name} rendered silence")
                        }
                    } finally {
                        if (stream != 0) { bass.BASS_StreamFree(stream); stream = 0 }
                        if (font != 0) { midi.BASS_MIDI_FontFree(font); font = 0 }
                    }
                }
            }
        } finally {
            bass.BASS_Free()
            dir.listFiles()?.forEach { it.delete() }; dir.delete()
        }
    }
}
