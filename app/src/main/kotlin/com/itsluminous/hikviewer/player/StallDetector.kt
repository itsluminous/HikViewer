package com.itsluminous.hikviewer.player

/**
 * Decides when a decoder that is being fed has stopped producing pictures.
 *
 * Two rules, either is enough: no picture for [STALL_MILLIS] while input keeps going in (the
 * classic watchdog, e.g. after a resolution change on the MiTV), or [WEDGE_FRAMES] pictures went
 * in since the last one came out and none has come out for [WEDGE_MILLIS]. The second rule is
 * what catches the MiTV's software H.264 decoder: under load with several instances
 * `OMX.google.h264.decoder` now and then keeps consuming input while producing nothing at all (no
 * error, no log line, queues empty). Since the stream it is fed declares no reordering
 * ([com.itsluminous.hikviewer.core.stream.H264SpsRewriter]) a healthy decoder holds at most a
 * handful of pictures (2–4 on the MiTV, 5 at the worst 5-minute sample), so twice that swallowed
 * is a wedge, not latency. The frame bound is what makes detection independent of the camera's
 * frame rate: at 12 fps the time floor decides (~1.2 s), at the 6–8 fps the user plans for the
 * recorder's sub-stream the ten pictures do (1.25–1.7 s), either way well under [STALL_MILLIS].
 * A decoder that is merely starved of input (the stream stopped) is never stalled: the stream has
 * its own watchdog.
 *
 * Pure Kotlin with time passed in, so the thresholds are unit-tested with a fake clock.
 */
class StallDetector(
    startedAtMillis: Long,
) {
    private var lastInputAt = Long.MIN_VALUE
    private var lastOutputAt = startedAtMillis
    private var inputsSinceOutput = 0

    /** True once at least one picture was handed to the codec since the last [reset]. */
    val hasInput: Boolean get() = lastInputAt != Long.MIN_VALUE

    /** Pictures fed since the last picture came out. */
    val swallowed: Int get() = inputsSinceOutput

    /** A picture was handed to the codec. */
    fun onInput(nowMillis: Long) {
        lastInputAt = nowMillis
        inputsSinceOutput++
    }

    /** A picture came out of the codec. */
    fun onOutput(nowMillis: Long) {
        lastOutputAt = nowMillis
        inputsSinceOutput = 0
    }

    /** The codec was flushed or restarted: start the clocks afresh, wait for input again. */
    fun reset(nowMillis: Long) {
        lastInputAt = Long.MIN_VALUE
        lastOutputAt = nowMillis
        inputsSinceOutput = 0
    }

    /** Milliseconds since the last output (since the start or reset while there was none). */
    fun outputAgeMillis(nowMillis: Long): Long = nowMillis - lastOutputAt

    /** Milliseconds since the last input; `Long.MAX_VALUE` while there was none. */
    fun inputAgeMillis(nowMillis: Long): Long = if (hasInput) nowMillis - lastInputAt else Long.MAX_VALUE

    /** True when input is being accepted but nothing comes out, by either rule. */
    fun isStalled(nowMillis: Long): Boolean {
        if (!hasInput || nowMillis - lastInputAt >= STALL_MILLIS) return false
        val silence = nowMillis - lastOutputAt
        return silence > STALL_MILLIS || (inputsSinceOutput >= WEDGE_FRAMES && silence > WEDGE_MILLIS)
    }

    /** Thresholds. */
    companion object {
        /** No output for this long while input is accepted → stalled. */
        const val STALL_MILLIS: Long = 3_000

        /**
         * This many pictures swallowed since the last output, with [WEDGE_MILLIS] of silence → wedged.
         * Twice the deepest healthy backlog measured on the MiTV (in−out ≤ 5 with no reordering).
         */
        const val WEDGE_FRAMES: Int = 10

        /** Shortest silence that counts as a wedge, so a slow first picture is not one. */
        const val WEDGE_MILLIS: Long = 1_200
    }
}
