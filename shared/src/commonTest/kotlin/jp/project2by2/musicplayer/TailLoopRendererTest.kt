package jp.project2by2.musicplayer

import jp.project2by2.musicplayer.audio.LoopDecoder
import jp.project2by2.musicplayer.audio.TailLoopRenderer
import kotlin.test.*

class TailLoopRendererTest {
    private class Synth : LoopDecoder {
        var position = 0L
        var tail = false
        var releaseCount = 0
        var prepareCount = 0
        override fun read(samples: FloatArray, frames: Int): Int {
            repeat(frames) { i ->
                // Release and new-note signals are distinguishable in both channels.
                samples[i * 2] = if (tail) 0.25f else if (position == 0L) 0.5f else 0f
                samples[i * 2 + 1] = if (tail) -0.25f else 0f
                position++
            }
            return frames
        }
        override fun prepare(frame: Long) { position = frame; tail = false; prepareCount++ }
        override fun beginTail() { tail = true; releaseCount++ }
        override fun frameAtTick(tick: Long) = tick
    }

    @Test fun newAttackStartsOnBoundaryWhileOldStereoTailContinues() {
        val a = Synth(); val b = Synth()
        val renderer = TailLoopRenderer(a, b, 100, sampleRate = 100)
        renderer.configure(0, 100) { true }
        val samples = FloatArray(400)
        assertEquals(200, renderer.render(samples, 200))
        assertEquals(0.5f, samples[0])
        assertEquals(0.75f, samples[200], "Old release + new attack at exactly frame 100")
        assertEquals(-0.25f, samples[201])
        assertEquals(0.25f, samples[202], "No duplicate new attack")
        assertEquals(0f, samples[300], "Tail has reached zero before recycling")
        assertEquals(1, a.releaseCount)
    }

    @Test fun thousandsOfShortLoopsHaveNoDroppedFramesOrExtraDecoders() {
        for (length in listOf(1L, 2L, 7L, 100L)) {
            val a = Synth(); val b = Synth()
            val renderer = TailLoopRenderer(a, b, length)
            var loops = 0
            renderer.configure(0, length) { loops++; true }
            val samples = FloatArray(2048)
            repeat(20) {
                assertEquals(1024, renderer.render(samples, 1024))
                renderer.songFrame((it + 1) * 1024L)
                assertTrue(samples.all { sample -> sample.isFinite() })
            }
            assertEquals(((20480L - 1) / length).toInt(), loops)
            assertEquals(loops, a.releaseCount + b.releaseCount)
        }
    }

    @Test fun manualSeekRemovesTailAndMapsAudiblePositionRatherThanDecodePosition() {
        val renderer = TailLoopRenderer(Synth(), Synth(), 100, sampleRate = 100)
        renderer.configure(20, 100) { true }
        val samples = FloatArray(240)
        renderer.render(samples, 120)
        assertEquals(90, renderer.songFrame(90)) // playhead has not reached the buffered loop yet
        assertEquals(0, renderer.audibleLoopCount)
        assertEquals(20, renderer.songFrame(100))
        assertEquals(1, renderer.audibleLoopCount)
        assertEquals(40, renderer.songFrame(120))
        renderer.seek(30)
        assertEquals(30, renderer.songFrame(0))
        assertEquals(10, renderer.render(samples, 10))
        assertTrue(samples.take(20).all { it == 0f }, "Seeking must clear the old release")
    }

    @Test fun disablingLoopAtNextBoundaryEndsOnceWithoutAnotherAttack() {
        val renderer = TailLoopRenderer(Synth(), Synth(), 100)
        var repeat = true
        var boundaries = 0
        renderer.configure(0, 100) { boundaries++; repeat }
        val samples = FloatArray(400)
        assertEquals(150, renderer.render(samples, 150))
        repeat = false
        assertEquals(50, renderer.render(samples, 200))
        assertTrue(renderer.ended)
        assertEquals(0, renderer.render(samples, 200))
        assertEquals(2, boundaries)
        renderer.seek(0)
        assertEquals(100, renderer.render(samples, 200))
    }

    @Test fun invalidOrEmptyLoopCannotSpinOnAudioThread() {
        for ((start, end) in listOf(0L to 0L, 100L to 100L, 100L to 20L)) {
            val renderer = TailLoopRenderer(Synth(), Synth(), 100)
            renderer.configure(start, end) { error("An empty loop must not repeat") }
            assertEquals(end.toInt(), renderer.render(FloatArray(400), 200))
            assertTrue(renderer.ended)
        }
    }
}
