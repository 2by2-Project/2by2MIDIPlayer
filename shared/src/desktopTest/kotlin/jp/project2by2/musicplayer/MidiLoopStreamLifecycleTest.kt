package jp.project2by2.musicplayer

import jp.project2by2.musicplayer.audio.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.test.*

class MidiLoopStreamLifecycleTest {
    private class Decoder : NativeMidiDecoder {
        override val durationFrames = 44100L
        override val durationTicks = 960L
        var closeCount = 0
        var onRead: () -> Unit = {}
        val attributes = mutableMapOf<Int, Float>()
        var assignedFont = 0
        override fun read(samples: FloatArray, frames: Int): Int {
            check(closeCount == 0)
            onRead()
            check(closeCount == 0) { "Decoder was freed during rendering" }
            samples.fill(0.1f, 0, frames * 2)
            return frames
        }
        override fun prepare(frame: Long) { check(closeCount == 0) }
        override fun beginTail() { check(closeCount == 0) }
        override fun frameAtTick(tick: Long) = tick * 44100 / 960
        override fun setAttribute(attribute: Int, value: Float) { attributes[attribute] = value }
        override fun setFlags(flags: Int, mask: Int) = Unit
        override fun setFont(font: Int) { assignedFont = font }
        override fun close() { closeCount++ }
    }

    private class Output : LoopOutputBackend {
        private val lock = ReentrantLock()
        private var callback: ((ByteBuffer, Int) -> Int)? = null
        var createFailure = false
        var freeCount = 0
        var position = 0L
        val freeEntered = CountDownLatch(1)
        override fun create(render: (ByteBuffer, Int) -> Int): Int {
            if (createFailure) return 0
            callback = render
            return 123
        }
        override fun lock(handle: Int, locked: Boolean) { if (locked) lock.lock() else lock.unlock() }
        override fun positionBytes(handle: Int) = position
        override fun reset(handle: Int) { position = 0 }
        override fun free(handle: Int) {
            freeEntered.countDown()
            lock.withLock { callback = null; freeCount++ }
        }
        fun render(): Int = lock.withLock {
            val result = callback!!.invoke(ByteBuffer.allocateDirect(8192), 8192)
            position += result and Int.MAX_VALUE
            result
        }
    }

    @Test fun closeWaitsForRenderingBeforeFreeingEitherDecoderAndIsIdempotent() {
        val a = Decoder(); val b = Decoder(); val output = Output()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        a.onRead = { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        val stream = MidiLoopStream(output, a, b) { throw AssertionError(it) }
        val workers = Executors.newFixedThreadPool(2) { Thread(it).apply { isDaemon = true } }
        try {
            val rendering = workers.submit<Int> { output.render() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = workers.submit { stream.close() }
            assertTrue(output.freeEntered.await(5, TimeUnit.SECONDS))
            assertEquals(0, a.closeCount)
            assertEquals(0, b.closeCount)
            finish.countDown()
            assertEquals(8192, rendering.get(5, TimeUnit.SECONDS))
            closing.get(5, TimeUnit.SECONDS)
            stream.close()
            assertEquals(1, output.freeCount)
            assertEquals(1, a.closeCount)
            assertEquals(1, b.closeCount)
        } finally { finish.countDown(); workers.shutdownNow(); stream.close() }
    }

    @Test fun renderingFailureStopsOutputWithoutUnwindingThroughNativeCallback() {
        val a = Decoder(); val b = Decoder(); val output = Output()
        var reports = 0
        MidiLoopStream(output, a, b) { reports++ }.use { stream ->
            a.onRead = { error("Native decoder failure") }
            assertEquals(Int.MIN_VALUE, output.render())
            assertNotNull(stream.failure)
            assertEquals(Int.MIN_VALUE, output.render())
            assertEquals(1, reports)
            a.onRead = {}
            stream.seek(0)
            assertEquals(8192, output.render())
            assertNull(stream.failure)
        }
    }

    @Test fun creationFailureReleasesBothDecoders() {
        val a = Decoder(); val b = Decoder()
        assertFailsWith<IllegalStateException> {
            MidiLoopStream(Output().apply { createFailure = true }, a, b) {}
        }
        assertEquals(1, a.closeCount)
        assertEquals(1, b.closeCount)
    }

    @Test fun settingsReachBothSynthsAndOutputHandleSurvivesLoopsAndSeeks() {
        val a = Decoder(); val b = Decoder(); val output = Output()
        MidiLoopStream(output, a, b) {}.use { stream ->
            stream.setAttribute(0x12009, 1.5f)
            stream.setFont(789)
            assertEquals(a.attributes, b.attributes)
            assertEquals(1.5f, b.attributes[0x12009])
            assertEquals(789, a.assignedFont)
            assertEquals(789, b.assignedFont)
            stream.configure(0, 10) { true }
            repeat(100) { assertEquals(8192, output.render()) }
            assertTrue(stream.audibleLoopCount() > 100)
            stream.seek(0)
            assertEquals(0, stream.audibleLoopCount())
            assertEquals(123, stream.output)
        }
    }

    @Test fun extendingStoppedPreviewCanContinueAtItsOldEndpoint() {
        val output = Output()
        MidiLoopStream(output, Decoder(), Decoder()) {}.use { stream ->
            stream.configure(0, 10) { false }
            assertEquals(441 * 8 or Int.MIN_VALUE, output.render())
            assertEquals(10, stream.positionMs())
            stream.configure(0, 100) { false }
            assertEquals(10, stream.positionMs())
            assertEquals(8192, output.render())
            assertTrue(stream.positionMs() > 10)
        }
    }
}
