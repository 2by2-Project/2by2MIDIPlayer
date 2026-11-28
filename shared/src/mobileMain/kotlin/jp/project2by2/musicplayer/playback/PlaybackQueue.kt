package jp.project2by2.musicplayer.playback

import kotlin.random.Random

/**
 * The play queue of Android's PlaybackService: a folder, search result or playlist that wraps
 * around at either end, with a shuffled order that starts from the current track.
 */
class PlaybackQueue(private val random: Random = Random.Default) {
    var items: List<String> = emptyList()
        private set
    var title: String? = null
        private set
    /** Playlist id when the queue is a saved playlist. */
    var playlistId: String? = null
        private set
    private var shuffledOrder: List<Int> = emptyList()
    private var shuffledCursor = -1

    fun set(items: List<String>, title: String?, playlistId: String? = null): Boolean {
        val normalized = items.filter { it.isNotBlank() }.distinct()
        if (normalized.isEmpty()) return false
        this.items = normalized
        this.title = title?.takeIf { it.isNotBlank() }
        this.playlistId = playlistId
        resetShuffle()
        return true
    }

    fun next(current: String?, shuffle: Boolean): String? {
        val queue = items
        if (queue.isEmpty()) return null
        val index = current?.let(queue::indexOf) ?: -1
        if (index < 0) return queue.first()
        if (shuffle) return fromShuffled(queue, index, forward = true)
        return queue[(index + 1) % queue.size]
    }

    fun previous(current: String?, shuffle: Boolean): String? {
        val queue = items
        if (queue.isEmpty()) return null
        val index = current?.let(queue::indexOf) ?: -1
        if (index < 0) return queue.last()
        if (shuffle) return fromShuffled(queue, index, forward = false)
        return queue[if (index == 0) queue.lastIndex else index - 1]
    }

    /**
     * Inserts [target] after [current]; false when nothing is playing ("no active queue"). Without a
     * queue, [fallbackItems] (the list it was chosen from) becomes one when it holds [current], and
     * [onFallbackQueue] runs after the queue title has changed to [fallbackTitle].
     */
    fun enqueueNext(
        target: String, current: String?, fallbackItems: List<String>, fallbackTitle: String?,
        onFallbackQueue: () -> Unit = {},
    ): Boolean {
        val trimmed = target.trim()
        if (trimmed.isBlank() || current == null) return false
        var queue = items
        if (queue.isEmpty()) {
            val fallback = fallbackItems.filter { it.isNotBlank() }.distinct()
            queue = if (current in fallback) {
                playlistId = null
                title = fallbackTitle?.takeIf { it.isNotBlank() }
                onFallbackQueue()
                fallback
            } else listOf(current)
        }
        val mutable = queue.toMutableList()
        var index = mutable.indexOf(current)
        if (index < 0) {
            mutable.add(0, current)
            index = 0
        }
        mutable.add(index + 1, trimmed)
        items = mutable
        resetShuffle()
        return true
    }

    /** Keeps the queue pointing at renamed folders. */
    fun remap(transform: (String) -> String) {
        items = items.map(transform)
    }

    private fun fromShuffled(queue: List<String>, index: Int, forward: Boolean): String {
        if (queue.size <= 1) return queue.first()
        ensureShuffle(queue, index)
        val order = shuffledOrder
        if (order.isEmpty()) return queue[index]
        val cursor = if (shuffledCursor in order.indices) shuffledCursor else 0
        val next = if (forward) (cursor + 1) % order.size else if (cursor == 0) order.lastIndex else cursor - 1
        shuffledCursor = next
        return queue[order[next]]
    }

    private fun ensureShuffle(queue: List<String>, index: Int) {
        if (shuffledOrder.size != queue.size || shuffledOrder.any { it !in queue.indices } || shuffledOrder.isEmpty()) {
            rebuildShuffle(queue, index)
            return
        }
        val located = shuffledOrder.indexOf(index)
        if (located >= 0) shuffledCursor = located else rebuildShuffle(queue, index)
    }

    private fun rebuildShuffle(queue: List<String>, index: Int) {
        val others = queue.indices.filter { it != index }.toMutableList()
        others.shuffle(random)
        shuffledOrder = listOf(index) + others
        shuffledCursor = 0
    }

    private fun resetShuffle() {
        shuffledOrder = emptyList()
        shuffledCursor = -1
    }
}
