package com.itsluminous.hikviewer.player

import com.itsluminous.hikviewer.core.stream.AccessUnit
import java.util.ArrayDeque

/**
 * A software tile's jitter buffer: holds each picture until its presentation time, so pictures
 * that reached the device unevenly leave for the decoder evenly.
 *
 * Measured on the MiTV with eight software tiles, the relay delivers the 12 fps sub-stream with
 * an inter-picture gap of 83 ± 35 ms (max 260 ms) and, since the decoder renders what it is fed as
 * soon as it is decoded, the screen showed the same 83 ± 41 ms. The pacer maps the stream's own
 * clock (RTP timestamps, [AccessUnit.presentationTimeUs]) onto the device clock once, at the
 * first picture, plus [latencyMillis] of slack, and from then on releases every picture at
 * `base + (pts − basePts) + latency`. The slack is what absorbs the arrival jitter; it is also the
 * display latency the tile pays, so it is half a GOP (500 ms covers the 260 ms the relay was seen
 * to be late by with a margin) rather than the whole second.
 *
 * Two clocks never agree for ever, so the mapping is re-based when it has drifted: a picture that
 * arrives after it was due (the buffer ran dry: a late relay, or a camera clock slower than the
 * device's) re-buffers, the picture is due in [latencyMillis] again and the tile freezes for that
 * long once. A buffer whose newest picture would wait [catchUpMillis] beyond the latency (the
 * relay re-sending a backlog faster than real time after a hiccup, or a faster camera clock)
 * drops everything before the newest keyframe it holds, because a decoder can restart there and
 * nowhere else, and shifts what is left so the newest picture is due in [latencyMillis] again.
 * The [capacity] bound trims the same way; when that frees nothing (the whole buffer is one GOP)
 * it drops the lot and waits for the next keyframe rather than hand the decoder a picture whose
 * reference it has never seen. Pure Kotlin with the time passed in so every behaviour is tested
 * with a fake clock; not thread-safe, the decoder calls it under its own lock.
 */
class FramePacer(
    /** How long a picture waits past its mapped presentation time: the jitter it can absorb and the latency it costs. */
    private val latencyMillis: Long = DEFAULT_LATENCY_MILLIS,
    /** A picture that would wait this much longer than [latencyMillis] is a backlog to catch up on (drop to the newest keyframe). */
    private val catchUpMillis: Long = DEFAULT_CATCH_UP_MILLIS,
    /** Hard bound on buffered pictures; at it the buffer is caught up, or emptied when that frees nothing. */
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Entry(
        val unit: AccessUnit,
        var dueMillis: Long,
    )

    private val queue = ArrayDeque<Entry>()
    private var baseWallMillis = 0L
    private var basePtsMicros = 0L
    private var mapped = false
    private var lastPtsMicros = 0L

    /** Pictures waiting. */
    val size: Int get() = queue.size

    /** Times the mapping was re-based because a picture arrived after it was due. */
    var rebuffers: Int = 0
        private set

    /** Times the buffer was caught up (pictures dropped to the newest keyframe and the rest played from now). */
    var catchUps: Int = 0
        private set

    /** Pictures dropped by catch-ups and the capacity bound. */
    var dropped: Int = 0
        private set

    /**
     * Times the stream's clock jumped backwards and the mapping was re-made (a new stream behind
     * the same socket, or a recorder whose timestamps glitch). Costs the same [latencyMillis]
     * hold as a re-buffer, so it is counted like one.
     */
    var remaps: Int = 0
        private set

    /** Admits [unit], which arrived at [nowMillis]. */
    fun offer(
        unit: AccessUnit,
        nowMillis: Long,
    ) {
        val pts = unit.presentationTimeUs
        val due =
            if (pts == null) {
                nowMillis // no clock to pace by: straight through, after whatever is queued
            } else {
                if (mapped && pts < lastPtsMicros) remaps++
                if (!mapped || pts < lastPtsMicros) rebase(pts, nowMillis)
                var d = baseWallMillis + (pts - basePtsMicros) / MICROS_PER_MILLI + latencyMillis
                if (d < nowMillis) {
                    rebuffers++
                    rebase(pts, nowMillis)
                    d = nowMillis + latencyMillis
                }
                lastPtsMicros = pts
                d
            }
        if (queue.size >= capacity && !makeRoom(nowMillis, unit.isKeyframe)) {
            dropped++ // the incoming picture: it would have followed a GOP that was just emptied
            return
        }
        // Due times never go backwards within the queue, whatever the clocks did.
        val last = queue.peekLast()
        val ordered = if (last != null && due < last.dueMillis) last.dueMillis else due
        queue.addLast(Entry(unit, ordered))
        if (ordered - nowMillis > latencyMillis + catchUpMillis) catchUp(nowMillis)
    }

    /**
     * The buffer is full: catch up (drop to the newest keyframe). When that frees nothing the
     * buffer is a single GOP, so dropping its oldest picture would leave the decoder P-frames
     * without their reference; the whole GOP goes instead, and so does an incoming picture that
     * is not a keyframe. Returns whether the incoming picture may be queued.
     */
    private fun makeRoom(
        nowMillis: Long,
        incomingIsKeyframe: Boolean,
    ): Boolean {
        catchUp(nowMillis)
        if (queue.size < capacity) return true
        dropped += queue.size
        queue.clear()
        return incomingIsKeyframe
    }

    /** The next picture if it is due at [nowMillis], else null. */
    fun poll(nowMillis: Long): AccessUnit? {
        val head = queue.peekFirst() ?: return null
        if (head.dueMillis > nowMillis) return null
        queue.pollFirst()
        return head.unit
    }

    /** When the next picture is due, or null when nothing waits. */
    fun nextDueMillis(): Long? = queue.peekFirst()?.dueMillis

    /** Pictures that are due at [nowMillis] and still waiting: the decode-lag signal, not the buffer's fill. */
    fun overdue(nowMillis: Long): Int {
        var n = 0
        for (e in queue) {
            if (e.dueMillis > nowMillis) break
            n++
        }
        return n
    }

    /** Forgets the pictures and the clock mapping (the decoder is going away or restarting). */
    fun clear() {
        queue.clear()
        mapped = false
    }

    private fun rebase(
        ptsMicros: Long,
        nowMillis: Long,
    ) {
        baseWallMillis = nowMillis
        basePtsMicros = ptsMicros
        mapped = true
    }

    /**
     * Too far behind: drop up to the newest keyframe (a decoder cannot pick up anywhere else), then
     * shift what remains so the newest picture is due in [latencyMillis] again. Pictures that
     * shift to before now are fed as fast as the codec takes them: a brief fast-forward, after
     * which the tile is back at its target latency instead of staying a backlog behind.
     */
    private fun catchUp(nowMillis: Long) {
        catchUps++
        var newestKeyframe: Entry? = null
        for (e in queue) if (e.unit.isKeyframe) newestKeyframe = e
        if (newestKeyframe != null) {
            while (queue.peekFirst() !== newestKeyframe) {
                queue.pollFirst()
                dropped++
            }
        }
        val newest = queue.peekLast() ?: return
        val shift = newest.dueMillis - (nowMillis + latencyMillis)
        if (shift <= 0) return
        for (e in queue) e.dueMillis -= shift
        baseWallMillis -= shift
    }

    /** Defaults. */
    companion object {
        /** Half a 1 s GOP: covers the 260 ms the relay was measured to be late by, at half the latency of a full GOP. */
        const val DEFAULT_LATENCY_MILLIS: Long = 500

        /**
         * A second beyond the target latency is a backlog to drop, not latency to keep: after a
         * network hiccup on the TV the relay re-sent 2-3 s of pictures in a burst and, with this at
         * 2 s, eight tiles then sat 1-2.25 s behind for good (`paced=12-27`). With the drop to the
         * newest keyframe the tile is at most a GOP and a half behind live after a catch-up.
         */
        const val DEFAULT_CATCH_UP_MILLIS: Long = 1_000

        /** Enough for two GOPs of a 15 fps camera; a camera with longer GOPs than that at this fill is dropped to its next keyframe. */
        const val DEFAULT_CAPACITY: Int = 30

        private const val MICROS_PER_MILLI = 1_000L
    }
}
