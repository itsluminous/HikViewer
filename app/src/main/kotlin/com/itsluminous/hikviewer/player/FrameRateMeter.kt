package com.itsluminous.hikviewer.player

import java.util.ArrayDeque

/**
 * Frames per second over a sliding [windowMillis], fed with the wall-clock time of each decoder
 * output (the moment a picture is released to the surface), so it reports what is actually
 * rendered rather than the stream's nominal rate. Not thread-safe; callers hold their own lock.
 */
class FrameRateMeter(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val stamps = ArrayDeque<Long>()

    /** A picture came out at [nowMillis]. */
    fun onFrame(nowMillis: Long) {
        stamps.addLast(nowMillis)
        trim(nowMillis)
    }

    /** Frames per second as of [nowMillis]; 0 until a full window has elapsed since the first frame. */
    fun fps(nowMillis: Long): Float {
        trim(nowMillis)
        if (stamps.isEmpty()) return 0f
        return stamps.size * MILLIS_PER_SECOND / windowMillis.toFloat()
    }

    /** Forgets everything (stream restart). */
    fun reset() = stamps.clear()

    private fun trim(nowMillis: Long) {
        while (stamps.isNotEmpty() && nowMillis - stamps.first() > windowMillis) stamps.pollFirst()
    }

    /** Tunables. */
    companion object {
        /** Two seconds: long enough to be steady at 1 fps keyframe tiles, short enough to follow drops. */
        const val DEFAULT_WINDOW_MILLIS: Long = 2_000
        private const val MILLIS_PER_SECOND = 1_000f
    }
}
