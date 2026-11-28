package jp.project2by2.musicplayer.playback

import kotlin.random.Random
import kotlin.test.*

class PlaybackQueueTest {
    @Test fun nextAndPreviousWrapAroundTheQueue() {
        val queue = PlaybackQueue()
        assertTrue(queue.set(listOf("a", "b", "", "c", "b"), " Folder "))
        assertEquals(listOf("a", "b", "c"), queue.items)
        assertEquals(" Folder ", queue.title)
        assertEquals("b", queue.next("a", shuffle = false))
        assertEquals("a", queue.next("c", shuffle = false))
        assertEquals("c", queue.previous("a", shuffle = false))
        // A song outside the queue starts it from either end.
        assertEquals("a", queue.next("x", shuffle = false))
        assertEquals("c", queue.previous("x", shuffle = false))
        assertFalse(queue.set(listOf("", " "), "Empty"))
    }

    @Test fun shuffleVisitsEverySongBeforeRepeatingAndCanGoBack() {
        val queue = PlaybackQueue(Random(7))
        queue.set((1..6).map { "s$it" }, null)
        var current = "s3"
        val visited = mutableListOf(current)
        repeat(5) {
            current = queue.next(current, shuffle = true)!!
            visited += current
        }
        assertEquals((1..6).map { "s$it" }.toSet(), visited.toSet())
        assertEquals(visited[4], queue.previous(current, shuffle = true))
    }

    @Test fun playNextInsertsAfterTheCurrentSong() {
        val queue = PlaybackQueue()
        assertFalse(queue.enqueueNext("x", current = null, fallbackItems = emptyList(), fallbackTitle = null))
        queue.set(listOf("a", "b", "c"), "Folder")
        assertTrue(queue.enqueueNext(" x ", "b", emptyList(), null))
        assertEquals(listOf("a", "b", "x", "c"), queue.items)
    }

    @Test fun withoutAQueueTheSourceListBecomesOne() {
        val queue = PlaybackQueue()
        var fallbackUsed = false
        assertTrue(queue.enqueueNext("x", "b", listOf("a", "b", "c"), "Search") { fallbackUsed = true })
        assertTrue(fallbackUsed)
        assertEquals("Search", queue.title)
        assertEquals(listOf("a", "b", "x", "c"), queue.items)

        val single = PlaybackQueue()
        assertTrue(single.enqueueNext("x", "b", listOf("a", "c"), "Other"))
        assertEquals(listOf("b", "x"), single.items)
        assertNull(single.title)
    }
}

class LoopFadePolicyTest {
    @Test fun loopEnabledRepeatsForever() {
        val policy = LoopFadePolicy()
        repeat(5) { assertEquals(LoopBoundaryAction.Repeat, policy.onBoundary(true, hasLoopStart = true, fadeInProgress = false)) }
        assertEquals(LoopBoundaryAction.Repeat, policy.onBoundary(true, hasLoopStart = false, fadeInProgress = false))
    }

    @Test fun songWithoutLoopStartEnds() {
        assertEquals(LoopBoundaryAction.End, LoopFadePolicy().onBoundary(false, hasLoopStart = false, fadeInProgress = false))
    }

    @Test fun loopStartRepeatsOnceThenFadesAndKeepsLoopingUnderTheFade() {
        val policy = LoopFadePolicy()
        assertEquals(LoopBoundaryAction.Repeat, policy.onBoundary(false, true, false))
        assertEquals(LoopBoundaryAction.RepeatAndFade, policy.onBoundary(false, true, false))
        assertEquals(LoopBoundaryAction.Repeat, policy.onBoundary(false, true, fadeInProgress = true))
        policy.reset()
        assertEquals(LoopBoundaryAction.Repeat, policy.onBoundary(false, true, false))
        // Turning loop on clears the count.
        policy.onBoundary(true, true, false)
        assertEquals(LoopBoundaryAction.Repeat, policy.onBoundary(false, true, false))
        assertEquals(LoopBoundaryAction.RepeatAndFade, policy.onBoundary(false, true, false))
    }
}
