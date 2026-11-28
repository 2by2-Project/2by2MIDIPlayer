package jp.project2by2.musicplayer.desktop

private const val BOUNDARY_TOLERANCE_MS = 50L

/** Further into a measure than this, going back first returns to the start of that measure. */
private const val RESTART_THRESHOLD_MS = 300L

/**
 * Start of the measure to jump to from [positionMs] (+1 next, -1 back), or null when there is none.
 * Going back from the middle of a measure returns to that measure's start; from just after its start
 * (or from the first measure) it goes to the previous measure's start, or the very beginning.
 * [measureMs] holds each measure's start in ascending order.
 */
internal fun measureSkipTarget(measureMs: List<Long>, positionMs: Long, direction: Int): Long? {
    if (measureMs.isEmpty() || direction == 0) return null
    // A seek can land a few ms before the boundary it aimed at; that still counts as being in the measure.
    val found = measureMs.binarySearch(positionMs + BOUNDARY_TOLERANCE_MS)
    // binarySearch returns -(insertion point) - 1 when absent; the measure is the one before that point.
    val current = (if (found >= 0) found else -found - 2).coerceAtLeast(0)
    if (direction < 0 && positionMs - measureMs[current] > RESTART_THRESHOLD_MS) return measureMs[current]
    val target = current + direction
    if (target > measureMs.lastIndex) return null
    return measureMs[target.coerceAtLeast(0)]
}
