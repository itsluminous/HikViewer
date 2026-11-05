package com.itsluminous.hikviewer.ui.playback

import com.itsluminous.hikviewer.core.stream.AccessUnit
import java.time.LocalDateTime
import java.util.ArrayDeque

/**
 * The keyframes a [PlaybackStepper] has fetched, keyed by recorder time, at most [capacity] of
 * them (the oldest fetched goes first). A step at time `t` is served by the keyframe at `t` or
 * within a tolerance after it (the nearest; [TOLERANCE_SECONDS] unless the caller's stride is
 * shorter), which is where a window opened at `t` puts its first keyframe and where a recorder
 * with a GOP longer than a second puts the next one; failing that, the nearest keyframe up to
 * the tolerance before `t`, so a picture that came a little early is shown rather than nothing. Coverage ([has]) is the strict half of that rule,
 * at or after `t`: a window that has delivered a keyframe shortly *before* a step has not
 * delivered that step yet. The entry served last is not served again (a turn-around would
 * otherwise show the same picture twice in a row), but any other may be, so reversing walks back
 * over keyframes that were already shown forward. Not thread-safe: the stepper owns it on one
 * thread.
 */
class KeyframeRing(
    private val capacity: Int,
) {
    /** One fetched keyframe. */
    class Entry(
        /** Recorder time of the picture. */
        val time: LocalDateTime,
        /** The picture, with its parameter sets. */
        val unit: AccessUnit,
    )

    private val entries = ArrayDeque<Entry>()
    private var lastServed: Entry? = null

    /** Keyframes held. */
    val size: Int get() = entries.size

    /** Adds [unit] at [time]; a second keyframe at the same second replaces the first. */
    fun add(
        time: LocalDateTime,
        unit: AccessUnit,
    ) {
        entries.removeIf { it.time == time }
        if (entries.size >= capacity) entries.pollFirst()
        entries.addLast(Entry(time, unit))
    }

    /** True when a keyframe at [time], or within [toleranceSeconds] after it, is held. */
    fun has(
        time: LocalDateTime,
        toleranceSeconds: Long = TOLERANCE_SECONDS,
    ): Boolean = after(time, toleranceSeconds, except = null) != null

    /** The keyframe for the step at [time], marked as served, or null when none is within [toleranceSeconds]. */
    fun serve(
        time: LocalDateTime,
        toleranceSeconds: Long = TOLERANCE_SECONDS,
    ): Entry? {
        val entry =
            after(time, toleranceSeconds, lastServed)
                ?: before(time, toleranceSeconds, lastServed)
                ?: return null
        lastServed = entry
        return entry
    }

    /** Forgets everything. */
    fun clear() {
        entries.clear()
        lastServed = null
    }

    /** The earliest entry in `[time, time + tolerance]`. */
    private fun after(
        time: LocalDateTime,
        toleranceSeconds: Long,
        except: Entry?,
    ): Entry? {
        val high = time.plusSeconds(toleranceSeconds)
        return entries
            .filter { it !== except && !it.time.isBefore(time) && !it.time.isAfter(high) }
            .minByOrNull { it.time }
    }

    /** The latest entry in `[time - tolerance, time)`. */
    private fun before(
        time: LocalDateTime,
        toleranceSeconds: Long,
        except: Entry?,
    ): Entry? {
        val low = time.minusSeconds(toleranceSeconds)
        return entries
            .filter { it !== except && it.time.isBefore(time) && !it.time.isBefore(low) }
            .maxByOrNull { it.time }
    }

    /** Tunables. */
    companion object {
        /**
         * How far from its step a keyframe may be and still stand for it: two GOPs of a typical
         * recorder. A caller whose steps are closer than that passes less, or one keyframe would
         * stand for two steps.
         */
        const val TOLERANCE_SECONDS: Long = 2
    }
}
