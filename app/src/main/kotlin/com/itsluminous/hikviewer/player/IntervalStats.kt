package com.itsluminous.hikviewer.player

import kotlin.math.sqrt

/**
 * Mean, standard deviation and maximum of the gaps between consecutive events, for the per-tile
 * diagnostics line: fed with the time of every picture that arrives from the stream and of every
 * picture that reaches the screen, it says whether a tile is shown evenly (12 gaps of 83 ms) or
 * in bursts (a GOP in 50 ms, then a second of nothing), which no frames-per-second number can.
 *
 * Welford's running moments: three doubles and no allocation per event, because it runs on the
 * stream and codec threads for every picture of every tile. [describe] builds the text once per
 * report and [reset] starts the next window. Not thread-safe; callers hold their own lock.
 */
class IntervalStats {
    private var lastAt = Long.MIN_VALUE
    private var count = 0
    private var mean = 0.0
    private var m2 = 0.0
    private var max = 0L

    /** Gaps measured since the last [reset]. */
    val gaps: Int get() = count

    /** Mean gap in milliseconds; 0 before the second event. */
    val meanMillis: Double get() = mean

    /** Population standard deviation of the gaps in milliseconds; 0 with fewer than two gaps. */
    val stddevMillis: Double get() = if (count < 2) 0.0 else sqrt(m2 / count)

    /** Longest gap in milliseconds. */
    val maxMillis: Long get() = max

    /** An event happened at [nowMillis]. */
    fun onEvent(nowMillis: Long) {
        val previous = lastAt
        lastAt = nowMillis
        if (previous == Long.MIN_VALUE) return
        val gap = (nowMillis - previous).coerceAtLeast(0L)
        count++
        val delta = gap - mean
        mean += delta / count
        m2 += delta * (gap - mean)
        if (gap > max) max = gap
    }

    /** Forgets the gaps but keeps the last event, so the next window's first gap is a real one. */
    fun reset() {
        count = 0
        mean = 0.0
        m2 = 0.0
        max = 0L
    }

    /** Forgets everything, including the last event (the stream restarted). */
    fun clear() {
        reset()
        lastAt = Long.MIN_VALUE
    }

    /** `n=12 gap=83±4 max=91` (milliseconds), or `n=0` with nothing to say. */
    fun describe(): String =
        if (count == 0) "n=0" else "n=$count gap=${Math.round(mean)}±${Math.round(stddevMillis)} max=$max"
}
