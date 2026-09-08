package jp.project2by2.musicplayer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundledMidiTest {
    @Test
    fun bundledAndroidMidiFilesCanBeIndexedWithoutAndroid() {
        val directory = File(assertNotNull(javaClass.getResource("/demo")).toURI())
        val files = directory.listFiles().orEmpty().filter { it.extension.equals("mid", ignoreCase = true) }
        assertTrue(files.isNotEmpty(), "The Android demo corpus must be available")
        for (file in files) {
            val index = assertNotNull(parseSmfToPianoRollIndex(file.readBytes()), file.name)
            val metadata = parseMidiMetadata(file.readBytes())
            assertTrue((metadata.durationMs ?: 0) > 0, "Metadata: ${file.name}")
            assertTrue(index.notes.isNotEmpty(), file.name)
            assertTrue(index.totalTicks > 0 && index.totalDurationMs > 0, file.name)
            assertTrue(index.notes.zipWithNext().all { (a, b) -> a.startTick <= b.startTick }, file.name)
            assertTrue(index.tickTimeAnchors.zipWithNext().all { (a, b) -> a.tick < b.tick && a.ms <= b.ms }, file.name)
            assertTrue(index.notes.all { it.startTick < it.endTick && it.startMs <= it.endMs }, file.name)
        }
    }
}
