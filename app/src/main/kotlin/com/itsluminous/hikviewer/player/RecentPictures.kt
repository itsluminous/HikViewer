package com.itsluminous.hikviewer.player

/**
 * The last [capacity] pictures a decoder was fed and produced, for the stall log line.
 *
 * Written on every picture from the stream and callback threads, so it is primitive arrays and
 * nothing else: the previous `ArrayDeque<String>` built a string per input and boxed a `Long` per
 * output, ~300 small allocations a second with twelve tiles, against the no-per-frame-allocation
 * rule of the decode path. The text is built only when [describe] is called, which is once per
 * stall. Not thread-safe; the decoder calls it under its own lock.
 */
internal class RecentPictures(
    private val capacity: Int,
) {
    private val inPts = LongArray(capacity)
    private val inSize = IntArray(capacity) // negative marks a keyframe
    private var inCount = 0
    private val outPts = LongArray(capacity)
    private var outCount = 0

    /** A picture went in: presentation time in microseconds, its size and whether it was a keyframe. */
    fun recordIn(
        ptsMicros: Long,
        size: Int,
        keyframe: Boolean,
    ) {
        val slot = inCount++ % capacity
        inPts[slot] = ptsMicros / MICROS_PER_MILLI
        inSize[slot] = if (keyframe) -size else size
    }

    /** A picture came out at [ptsMicros]. */
    fun recordOut(ptsMicros: Long) {
        outPts[outCount++ % capacity] = ptsMicros / MICROS_PER_MILLI
    }

    /** `in[pts/size pts/sizeK …] out[pts …]`, oldest first, milliseconds, `K` for a keyframe. */
    fun describe(): String {
        val ins =
            ring(inCount) { i ->
                val size = inSize[i]
                "${inPts[i]}/${if (size < 0) "${-size}K" else "$size"}"
            }
        val outs = ring(outCount) { i -> outPts[i].toString() }
        return "in[$ins] out[$outs]"
    }

    private fun ring(
        count: Int,
        entry: (Int) -> String,
    ): String {
        val n = minOf(count, capacity)
        return (0 until n).joinToString(" ") { k -> entry((count - n + k) % capacity) }
    }

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
    }
}
