package jp.project2by2.musicplayer.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import java.io.File
import java.nio.file.Files
import javax.sound.midi.*
import kotlin.test.*

/** Proves that a file decoder can become an isolated tail without seeking it. */
class NativeTailTest {
    @Test fun filteredDecoderPreservesReleaseAcrossEndOfFileAndIgnoresLaterNotes() {
        val platform = NativePlatform.detect()
        val directory = File(System.getProperty("bass.native.dir"), platform.directory)
        val bass = Native.load(File(directory, platform.core).absolutePath, BassCore::class.java)
        val midi = Native.load(File(directory, platform.midi).absolutePath, BassMidi::class.java)
        assertTrue(bass.BASS_Init(0, 44100, 0, null, null))
        val temp = Files.createTempDirectory("bass-tail-").toFile()
        var font = 0
        val streams = mutableListOf<Int>()
        val unicode = if (platform == NativePlatform.Windows) Int.MIN_VALUE else 0
        fun path(file: File): Memory {
            val bytes = (file.absolutePath + '\u0000').toByteArray(if (unicode != 0) Charsets.UTF_16LE else Charsets.UTF_8)
            return Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        }
        try {
            val sf = File(temp, "tail.sf2").apply { writeBytes(testSoundFont(0)) }
            font = path(sf).use { midi.BASS_MIDI_FontInit(it, unicode) }
            assertNotEquals(0, font)
            fun decoder(extraNotes: Boolean): Int {
                val song = File(temp, "$extraNotes.mid")
                val sequence = Sequence(Sequence.PPQ, 480)
                sequence.createTrack().apply {
                    add(MidiEvent(ShortMessage(0x90, 60, 100), 0))
                    if (extraNotes) {
                        add(MidiEvent(ShortMessage(0x90, 90, 127), 240))
                        add(MidiEvent(ShortMessage(0xB0, 120, 0), 280))
                    }
                    add(MidiEvent(MetaMessage(0x2F, byteArrayOf(), 0), 480))
                }
                MidiSystem.write(sequence, 0, song)
                val stream = path(song).use {
                    midi.BASS_MIDI_StreamCreateFile(0, it, 0, 0, unicode or 0x200000 or 0x100 or 0x8000 or 0x1000 or 0x2000, 44100)
                }
                assertNotEquals(0, stream)
                streams += stream
                Memory(12).use {
                    it.setInt(0, font); it.setInt(4, -1); it.setInt(8, 0)
                    assertTrue(midi.BASS_MIDI_StreamSetFonts(stream, it, 1))
                }
                return stream
            }
            val reference = decoder(false)
            val extra = decoder(true)
            val filter = MidiFilter { _, _, _, _, _ -> false }
            fun read(stream: Int, frames: Int): FloatArray = Memory(frames * 8L).use {
                val count = bass.BASS_ChannelGetData(stream, it, frames * 8)
                assertEquals(frames * 8, count, "Decode failed: ${bass.BASS_ErrorGetCode()}")
                it.getFloatArray(0, frames * 2)
            }
            for (stream in streams) {
                read(stream, 8820) // 200ms: still sounding, before any later events.
                assertTrue(midi.BASS_MIDI_StreamSetFilter(stream, false, filter, null))
                assertTrue(midi.BASS_MIDI_StreamEvent(stream, 0, 18, 0)) // NOTESOFF
            }
            val expected = read(reference, 26460) // Pass EOT (500ms) with a 1s release.
            val actual = read(extra, 26460)
            assertContentEquals(expected, actual, "Events after the boundary must not affect the old voice")
            assertTrue(actual.takeLast(4410).any { kotlin.math.abs(it) > 0.0001f }, "Release was cut at EOT")

            val loopFile = File(temp, "loop.mid")
            val loopSequence = Sequence(Sequence.PPQ, 480)
            loopSequence.createTrack().apply {
                add(MidiEvent(ShortMessage(0xB0, 91, 127), 0))
                add(MidiEvent(ShortMessage(0x90, 60, 100), 192)) // 200ms silence before attack
                add(MidiEvent(MetaMessage(0x2F, byteArrayOf(), 0), 480))
            }
            MidiSystem.write(loopSequence, 0, loopFile)
            createDesktopLoopStream(bass, midi, loopFile, font, decodeOutput = true).use { audio ->
                audio.setFlags(0x2000, 0x2000)
                var loops = 0
                audio.configureTicks(0, 480) { loops++; true }
                assertEquals(500, audio.durationMs)
                read(audio.output, 22050)
                val overlap = read(audio.output, 4410)
                assertEquals(1, loops)
                assertEquals(1, audio.audibleLoopCount())
                assertNull(audio.failure)
                assertTrue(overlap.any { kotlin.math.abs(it) > 0.0001f }, "Old synth must sound during next cycle's silence")
                assertEquals(100, audio.positionMs())
                audio.seek(0)
                assertTrue(read(audio.output, 4410).all { kotlin.math.abs(it) < 0.000001f }, "Manual seek leaked old tail")
                repeat(100) { read(audio.output, 22050) }
                assertNull(audio.failure)
                assertEquals(100, audio.positionMs())
            }

            val reverbFontFile = File(temp, "reverb.sf2").apply { writeBytes(testSoundFont()) }
            val reverbFont = path(reverbFontFile).use { midi.BASS_MIDI_FontInit(it, unicode) }
            assertNotEquals(0, reverbFont)
            try {
                fun tailEnergy(effects: Boolean): Double {
                    return createDesktopLoopStream(bass, midi, loopFile, reverbFont, decodeOutput = true).use { audio ->
                        audio.setFlags(if (effects) 0 else 0x2000, 0x2000)
                        audio.setAttribute(0x12009, 2f)
                        audio.configureTicks(0, 480) { true }
                        read(audio.output, 22050)
                        val release = read(audio.output, 6615) // 150ms of next cycle's initial silence
                        // Ignore the first 50ms: this SoundFont's key release is only ~1ms.
                        release.drop(4410).sumOf { it.toDouble() * it }
                    }
                }
                val dryEnergy = tailEnergy(false)
                val wetEnergy = tailEnergy(true)
                assertTrue(wetEnergy > 0.000001 && wetEnergy > dryEnergy * 10,
                    "Old reverb was reset: dry=$dryEnergy wet=$wetEnergy")
            } finally { midi.BASS_MIDI_FontFree(reverbFont) }

            createDesktopLoopStream(bass, midi, loopFile, font, decodeOutput = true).use { audio ->
                var ended = 0
                val endSync = EndSync { _, _, _, _ -> ended++ }
                assertNotEquals(0, bass.BASS_ChannelSetSync(audio.output, 2, 0, endSync, null))
                audio.configureTicks(0, 480) { false }
                Memory(44100 * 8L).use { buffer ->
                    assertEquals(22050 * 8, bass.BASS_ChannelGetData(audio.output, buffer, 44100 * 8))
                    assertEquals(1, ended)
                }
                assertEquals(500, audio.positionMs())
                audio.seek(0)
                assertTrue(read(audio.output, 4410).all { it == 0f })
                audio.seek(audio.durationMs)
                Memory(8).use { assertTrue(bass.BASS_ChannelGetData(audio.output, it, 8) <= 0) }
                assertNull(audio.failure)
            }

            // Validate native tick conversion, custom boundaries and state restoration on the
            // shipped corpus, including tempo changes and MIDI ports, without audible playback.
            val demos = File(System.getProperty("midi.demo.dir")).listFiles()!!
                .filter { it.extension.equals("mid", true) || it.extension.equals("midi", true) }
            assertEquals(150, demos.size)
            for (song in demos) {
                createDesktopLoopStream(bass, midi, song, font, decodeOutput = true).use { audio ->
                    val timing = jp.project2by2.musicplayer.parseMidiTiming(song.readBytes())
                    var boundaries = 0
                    audio.configureTicks((timing.loopStartTick ?: 0).toLong(), audio.durationTicks) { boundaries++; true }
                    audio.seek((audio.durationMs - 10).coerceAtLeast(0))
                    read(audio.output, 4410)
                    assertNull(audio.failure, song.name)
                    assertTrue(boundaries > 0, "Boundary not reached: ${song.name}")
                }
            }
        } finally {
            streams.forEach { bass.BASS_StreamFree(it) }
            if (font != 0) midi.BASS_MIDI_FontFree(font)
            bass.BASS_Free()
            temp.listFiles()?.forEach { it.delete() }; temp.delete()
        }
    }
}
