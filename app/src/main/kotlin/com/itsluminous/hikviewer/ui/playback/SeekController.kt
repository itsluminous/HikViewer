package com.itsluminous.hikviewer.ui.playback

/**
 * The timing rules of seeking, free of Android and coroutines so they are tested with a fake clock.
 *
 * Two rules, both driven by [clock] (monotonic milliseconds):
 *
 *  * **Accelerating hold.** The first press of a held key moves the playhead by the smallest step;
 *    the auto-repeats that follow grow with how long the key has been down, on a YouTube-like
 *    schedule ([SCHEDULE]: 10 s, then 30 s, 1 min, 5 min, 15 min), so a short hold adjusts by
 *    seconds and a long one crosses the day. [release] (key up) resets it. The step is a function
 *    of hold *time*, not of the key's repeat count, so every remote accelerates alike.
 *  * **Debounce.** Every seek input (press, tap, drag sample) is recorded by [input]; the stream
 *    is reopened only once [DEBOUNCE_MILLIS] have passed with no further input ([dueIn] reaches 0),
 *    so a burst of presses or a drag opens one window, not one per event. The label meanwhile
 *    shows the target at once (the caller updates it on every input).
 */
class SeekController(
    private val clock: () -> Long,
    /** Quiet time after the last input before the reopen. */
    val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val schedule: List<Stage> = SCHEDULE,
) {
    /** One rung of the acceleration: from [fromHoldMillis] of holding on, each press moves [stepSeconds]. */
    data class Stage(
        val fromHoldMillis: Long,
        val stepSeconds: Long,
    )

    private var holdStart: Long? = null
    private var lastInputAt: Long? = null

    /** True while a key is held (between the first [press] and [release]). */
    val isHolding: Boolean get() = holdStart != null

    /**
     * A press or auto-repeat of a seek key: the seconds to move by, grown by how long the key has
     * been held. Counts as an input for the debounce.
     */
    fun press(): Long {
        val now = clock()
        val start = holdStart ?: now.also { holdStart = it }
        lastInputAt = now
        return stepFor(now - start)
    }

    /** The step for a key held [holdMillis] so far. */
    fun stepFor(holdMillis: Long): Long = schedule.last { holdMillis >= it.fromHoldMillis }.stepSeconds

    /** The key went up: the next press starts over at the smallest step. The debounce keeps running. */
    fun release() {
        holdStart = null
    }

    /** Any other seek input (a tap or a drag sample on the bar): restarts the quiet time. */
    fun input() {
        lastInputAt = clock()
    }

    /** Milliseconds until the pending reopen is due (0 = now); null when no seek is pending. */
    fun dueIn(): Long? = lastInputAt?.let { (it + debounceMillis - clock()).coerceAtLeast(0) }

    /** The reopen happened (or the seek was dropped): nothing is pending until the next input. */
    fun settle() {
        lastInputAt = null
    }

    /** Everything back to idle: no hold, nothing pending (the transport stopped). */
    fun reset() {
        holdStart = null
        lastInputAt = null
    }

    /** Defaults. */
    companion object {
        /** Quiet time after the last seek input before the stream is reopened. */
        const val DEBOUNCE_MILLIS: Long = 600

        /** Step per press by hold time: 10 s for the first second, then 30 s, 1 min, 5 min, 15 min. */
        val SCHEDULE: List<Stage> =
            listOf(
                Stage(0, 10),
                Stage(1_000, 30),
                Stage(2_500, 60),
                Stage(4_000, 5 * 60),
                Stage(6_000, 15 * 60),
            )
    }
}
