package jp.project2by2.musicplayer

import kotlin.math.roundToInt

fun msToTick(ms: Long, anchors: List<TickTimeAnchor>, totalTicks: Int): Int {
    if (anchors.isEmpty()) return 0
    val clampedMs = ms.coerceAtLeast(0L)
    if (clampedMs <= anchors.first().ms) return anchors.first().tick
    if (clampedMs >= anchors.last().ms) return anchors.last().tick

    var low = 0
    var high = anchors.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val value = anchors[mid].ms
        when {
            value < clampedMs -> low = mid + 1
            value > clampedMs -> high = mid - 1
            else -> return anchors[mid].tick.coerceIn(0, totalTicks)
        }
    }

    val right = low.coerceIn(1, anchors.lastIndex)
    val left = right - 1
    val a = anchors[left]
    val b = anchors[right]
    val spanMs = (b.ms - a.ms).coerceAtLeast(1L)
    val ratio = (clampedMs - a.ms).toDouble() / spanMs.toDouble()
    val tick = a.tick + ((b.tick - a.tick).toDouble() * ratio).roundToInt()
    return tick.coerceIn(0, totalTicks)
}
