package jp.project2by2.musicplayer.desktop

import jp.project2by2.musicplayer.soundfont.DlsConverter
import jp.project2by2.musicplayer.soundfont.SoundFontFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.*

class DlsConverterTest {
    private fun fixture(block: (File, File) -> Unit) {
        val dir = Files.createTempDirectory("dls-変換-").toFile()
        try { block(File(dir, "音源.DLS"), File(dir, "converted.sf2")) }
        finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }

    @Test fun preservesBankRangesTuningRegionLoopAndCueOffsets() = fixture { input, output ->
        input.writeBytes(testDls())
        DlsConverter.convert(input, output)
        val sf = SfChunks(output.readBytes())
        assertEquals("sfbk", output.readBytes().copyOfRange(8, 12).toString(Charsets.US_ASCII))
        val preset = sf["phdr"]
        assertEquals(0, preset.u16(20)); assertEquals(128, preset.u16(22))
        val generators = sf["igen"]
        val values = (0 until generators.size - 4 step 4).associate { generators.u16(it) to generators.u16(it + 2) }
        assertEquals(0x7f00, values[43]); assertEquals(0x7f01, values[44])
        assertEquals(1, values[53], "Cue 0 references the SECOND wave in the pool")
        assertEquals(64, values[58]); assertEquals(-1, values[51]!!.toShort().toInt())
        assertEquals(-25, values[52]!!.toShort().toInt())
        assertEquals(3, values[54], "Region's release loop overrides wave's forward loop")
        assertEquals(10, values[2]); assertEquals(-202, values[3]!!.toShort().toInt())
        assertEquals(100, values[48]); assertEquals(7, values[57])
        val samples = sf["smpl"]
        assertContentEquals(ByteArray(92), samples.copyOfRange(1024, 1116))
        assertContentEquals(ByteArray(92), samples.takeLast(92).toByteArray())
        val header = sf["shdr"]
        assertEquals(558, header.i32(46 + 20))
        assertEquals(1070, header.i32(46 + 24))
        assertEquals("EOS", header.copyOfRange(92, 95).toString(Charsets.US_ASCII))
    }

    @Test fun absentRegionWsmpInheritsWaveRootTuneAndLoop() = fixture { input, output ->
        input.writeBytes(testDls(regionSample = false))
        DlsConverter.convert(input, output)
        val g = SfChunks(output.readBytes())["igen"]
        val values = (0 until g.size - 4 step 4).associate { g.u16(it) to g.u16(it + 2) }
        assertEquals(60, values[58]); assertEquals(1, values[54]); assertEquals(0, values[2])
        assertEquals(0, values[51]); assertEquals(0, values[52])
    }

    @Test fun convertsUnsignedPcm8AndALawToSigned16() = fixture { input, output ->
        input.writeBytes(testDls(bits = 8))
        DlsConverter.convert(input, output)
        val pcm = SfChunks(output.readBytes())["smpl"]
        assertEquals(-32768, pcm.u16(0).toShort().toInt())
        assertEquals(0, pcm.u16(2)); assertEquals(32512, pcm.u16(4))
        input.writeBytes(testDls(bits = 8, format = 6))
        DlsConverter.convert(input, output)
        val alaw = SfChunks(output.readBytes())["smpl"]
        assertEquals(8, alaw.u16(0)); assertEquals(-8, alaw.u16(2).toShort().toInt())
    }

    @Test fun temporaryFontLifetimeAndOriginalSf2PassThrough() = fixture { input, output ->
        input.writeBytes(testDls())
        val prepared = SoundFontFiles.prepare(input, input.parentFile)
        assertTrue(prepared.converted); assertTrue(prepared.file.exists()); assertEquals("sf2", prepared.file.extension)
        prepared.close()
        assertFalse(prepared.file.exists()); assertTrue(input.exists())
        output.writeBytes(testSoundFont())
        SoundFontFiles.prepare(output, output.parentFile).use { assertEquals(output, it.file); assertFalse(it.converted) }
        assertTrue(output.exists())
    }

    @Test fun rejectsTruncatedOversizedInvalidCueAndUnsupportedSamplesWithoutLeavingOutput() = fixture { input, output ->
        val data = testDls()
        val variants = listOf(data.copyOf(data.size - 1), data.copyOf().apply { repeat(4) { this[4 + it] = -1 } },
            testDls(cueOffset = 123), testDls(bits = 24), testDls(channels = 2), testDls(loopLength = 900))
        for (bad in variants) {
            input.writeBytes(bad)
            assertFails { DlsConverter.convert(input, output) }
            assertFalse(output.exists())
            assertContentEquals(bad, input.readBytes())
        }
    }

    @Test fun cancellationDuringWaveWriteRemovesPartialTemporaryFile() = fixture { input, output ->
        input.writeBytes(testDls())
        assertFailsWith<CancellationException> {
            DlsConverter.convert(input, output) { if (output.exists()) throw CancellationException() }
        }
        assertFalse(output.exists()); assertTrue(input.exists())
    }

    @Test fun extensionAndHeaderDetectionBothTriggerConversion() = fixture { input, output ->
        input.writeBytes(testDls())
        input.copyTo(output)
        SoundFontFiles.prepare(output, output.parentFile).use { assertTrue(it.converted) }
        input.writeBytes(testSoundFont())
        assertFailsWith<IllegalArgumentException> { SoundFontFiles.prepare(input, input.parentFile) }
    }
}

internal fun testDls(bits: Int = 16, format: Int = 1, channels: Int = 1, regionSample: Boolean = true,
                     cueOffset: Int? = null, loopLength: Int = 300, percussion: Boolean = true): ByteArray {
    fun words(vararg values: Int) = ByteArrayOutputStream().apply { values.forEach { write(it); write(it ushr 8) } }.toByteArray()
    fun ints(vararg values: Int) = ByteArrayOutputStream().apply { values.forEach { v -> repeat(4) { write(v ushr (8 * it)) } } }.toByteArray()
    fun chunk(id: String, data: ByteArray) = id.toByteArray() + ints(data.size) + data + ByteArray(data.size % 2)
    fun list(type: String, vararg parts: ByteArray) = chunk("LIST", type.toByteArray() + parts.fold(byteArrayOf()) { a, b -> a + b })
    fun wsmp(root: Int, tune: Int, gain: Int, type: Int, start: Int, length: Int) =
        chunk("wsmp", ints(20) + words(root, tune) + ints(gain, 0, 1, 16, type, start, length))
    val samples = if (bits == 8) ByteArray(512) { if (format == 6) listOf(0xd5, 0x55)[it % 2].toByte() else listOf(0, 128, 255)[it % 3].toByte() }
        else words(*(0 until 512).map { (kotlin.math.sin(it * 2.0 * Math.PI / 64) * 12000).toInt() }.toIntArray())
    fun wave(name: String) = list("wave", chunk("fmt ", words(format, channels) + ints(44100, 44100 * bits / 8) + words(bits / 8, bits)),
        chunk("data", samples), wsmp(60, 0, 0, 0, 0, 512), list("INFO", chunk("INAM", (name + "\u0000").toByteArray())))
    val first = wave("First"); val second = wave("Second")
    val art = list("lart", chunk("art1", ints(8, 1) + words(0, 0, 0x209, 0) + ints(-1200 * 65536)))
    val region = list("rgn2", chunk("rgnh", words(0, 127, 1, 127, 0, 7)), chunk("wlnk", words(0, 0) + ints(1, 0)),
        if (regionSample) wsmp(64, -125, -40 * 65536, 1, 10, loopLength) else byteArrayOf())
    val instrument = list("ins ", chunk("insh", ints(1, if (percussion) Int.MIN_VALUE else 0, 0)), list("lrgn", region), art)
    return chunk("RIFF", "DLS ".toByteArray() + chunk("colh", ints(1)) +
        chunk("ptbl", ints(8, 2, cueOffset ?: first.size, 0)) + list("lins", instrument) + list("wvpl", first, second))
}

internal class SfChunks(private val bytes: ByteArray) {
    private val chunks = mutableMapOf<String, ByteArray>()
    init {
        fun visit(start: Int, end: Int) {
            var at = start
            while (at < end) {
                val id = bytes.copyOfRange(at, at + 4).toString(Charsets.US_ASCII)
                val size = bytes.i32(at + 4)
                require(size >= 0 && at.toLong() + 8 + size <= end)
                if (id == "LIST") visit(at + 12, at + 8 + size)
                else chunks[id] = bytes.copyOfRange(at + 8, at + 8 + size)
                at += 8 + size + size % 2
            }
            require(at == end)
        }
        assertEquals(bytes.size - 8, bytes.i32(4))
        visit(12, bytes.size)
    }
    operator fun get(id: String) = chunks.getValue(id)
}
internal fun ByteArray.u16(at: Int) = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getShort(at).toInt() and 65535
internal fun ByteArray.i32(at: Int) = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).getInt(at)
