package jp.project2by2.musicplayer.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.sin
import kotlin.test.*

/** Exercise the actual shipped native ABI without opening an audio device or playing sound. */
class NativeAudioTest {
    @Test fun nativeDecoderRendersSoundFontAndSeeksUnicodeMidiPath() {
        val platform = NativePlatform.detect()
        val directory = File(System.getProperty("bass.native.dir"), platform.directory)
        val bass = Native.load(File(directory, platform.core).absolutePath, BassCore::class.java)
        val midi = Native.load(File(directory, platform.midi).absolutePath, BassMidi::class.java)
        assertEquals(0x0204, bass.BASS_GetVersion() ushr 16)
        assertEquals(0x0204, midi.BASS_MIDI_GetVersion() ushr 16)
        assertTrue(bass.BASS_Init(0, 44100, 0, null, null), "BASS init: ${bass.BASS_ErrorGetCode()}")
        val temp = Files.createTempDirectory("bass-音源-").toFile()
        var font = 0
        var stream = 0
        try {
            val sf = File(temp, "テスト.sf2").apply { writeBytes(testSoundFont()) }
            val song = File(temp, "テスト.mid").apply {
                writeBytes(byteArrayOf(77,84,104,100,0,0,0,6,0,0,0,1,1,0xE0.toByte(),77,84,114,107,0,0,0,13,
                    0,0x90.toByte(),60,100,0x83.toByte(),0x60,0x80.toByte(),60,0,0,0xFF.toByte(),0x2F,0))
            }
            System.getProperty("native.fixture.dir")?.let { directory ->
                val output = File(directory).apply { mkdirs() }
                sf.copyTo(File(output, sf.name), overwrite = true)
                song.copyTo(File(output, song.name), overwrite = true)
            }
            val unicode = if (platform == NativePlatform.Windows) Int.MIN_VALUE else 0
            fun path(file: File): Memory {
                val bytes = (file.absolutePath + '\u0000').toByteArray(if (unicode != 0) Charsets.UTF_16LE else Charsets.UTF_8)
                return Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
            }
            font = path(sf).use { midi.BASS_MIDI_FontInit(it, unicode) }
            assertNotEquals(0, font, "FontInit: ${bass.BASS_ErrorGetCode()}")
            stream = path(song).use { midi.BASS_MIDI_StreamCreateFile(0, it, 0, 0, unicode or 0x200000 or 0x8000, 44100) }
            assertNotEquals(0, stream, "StreamCreate: ${bass.BASS_ErrorGetCode()}")
            assertTrue(bass.BASS_ChannelSetAttribute(stream, 0x12003, 40f))
            assertTrue(bass.BASS_ChannelSetAttribute(stream, 0x12009, 1.5f))
            assertNotEquals(-1, bass.BASS_ChannelFlags(stream, 0x2000, 0x2000))
            assertNotEquals(-1, bass.BASS_ChannelFlags(stream, 0, 0x2000))
            Memory(12).use {
                it.setInt(0, font); it.setInt(4, -1); it.setInt(8, 0)
                assertTrue(midi.BASS_MIDI_StreamSetFonts(stream, it, 1))
            }
            val length = bass.BASS_ChannelBytes2Seconds(stream, bass.BASS_ChannelGetLength(stream, 0))
            assertTrue(length in 0.49..0.51, "Unexpected MIDI duration: $length")
            Memory(32768).use {
                val read = bass.BASS_ChannelGetData(stream, it, 32768)
                assertTrue(read > 0, "Decode: ${bass.BASS_ErrorGetCode()}")
                assertTrue(it.getByteArray(0, read).any { sample -> sample.toInt() != 0 }, "SoundFont rendered only silence")
            }
            assertTrue(bass.BASS_ChannelSetPosition(stream, 240, 2))
            val seconds = bass.BASS_ChannelBytes2Seconds(stream, bass.BASS_ChannelGetPosition(stream, 0))
            assertTrue(seconds in 0.24..0.26, "Tick seek: $seconds")
        } finally {
            if (stream != 0) bass.BASS_StreamFree(stream)
            if (font != 0) midi.BASS_MIDI_FontFree(font)
            bass.BASS_Free()
            temp.listFiles()?.forEach { it.delete() }; temp.delete()
        }
    }
}

// Tiny generated sine-wave SoundFont. No third-party sample or SoundFont download is needed.
private fun testSoundFont(): ByteArray {
    fun text(s: String) = s.toByteArray(Charsets.US_ASCII)
    fun words(vararg values: Int) = ByteArrayOutputStream().apply {
        values.forEach { write(it and 255); write((it ushr 8) and 255) }
    }.toByteArray()
    fun dwords(vararg values: Int) = ByteArrayOutputStream().apply {
        values.forEach { v -> repeat(4) { write((v ushr (8 * it)) and 255) } }
    }.toByteArray()
    fun name(s: String) = text(s).copyOf(20)
    fun chunk(id: String, data: ByteArray) = text(id) + dwords(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    fun list(type: String, vararg children: ByteArray) = chunk("LIST", text(type) + children.fold(byteArrayOf()) { a, b -> a + b })
    val samples = (0 until 512).map { (sin(it * 2.0 * Math.PI / 64) * 12000).toInt() }.toIntArray()
    val info = list("INFO", chunk("ifil", words(2, 1)), chunk("isng", text("EMU8000\u0000")), chunk("INAM", text("Test\u0000\u0000")))
    val sdta = list("sdta", chunk("smpl", words(*samples) + ByteArray(92)))
    val pdta = list("pdta",
        chunk("phdr", name("Sine") + words(0, 0, 0) + dwords(0,0,0) + name("EOP") + words(0,0,1) + dwords(0,0,0)),
        chunk("pbag", words(0,0,1,0)), chunk("pmod", ByteArray(10)), chunk("pgen", words(41,0,0,0)),
        chunk("inst", name("Sine") + words(0) + name("EOI") + words(1)),
        chunk("ibag", words(0,0,2,0)), chunk("imod", ByteArray(10)), chunk("igen", words(54,1,53,0,0,0)),
        chunk("shdr", name("Sine") + dwords(0,512,0,512,44100) + byteArrayOf(60,0) + words(0,1) + name("EOS") + ByteArray(26)))
    return chunk("RIFF", text("sfbk") + info + sdta + pdta)
}
