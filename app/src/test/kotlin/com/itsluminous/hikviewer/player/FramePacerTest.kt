package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.VideoCodec
import org.junit.Test

class FramePacerTest {
    private val frameMicros = 1_000_000L / 12

    private fun unit(
        ptsMicros: Long?,
        keyframe: Boolean = false,
    ) = AccessUnit(VideoCodec.H264, ByteArray(1), ptsMicros, keyframe, null)

    /** Feeds [count] pictures with the stream's own 12 fps spacing, arriving at the given wall times. */
    private fun FramePacer.offerGop(
        firstPts: Long,
        arrivals: List<Long>,
    ) {
        arrivals.forEachIndexed { i, at -> offer(unit(firstPts + i * frameMicros, keyframe = i == 0), at) }
    }

    /** Drains the pacer by polling every millisecond from [from] to [to]; returns the release times. */
    private fun FramePacer.releasesBetween(
        from: Long,
        to: Long,
    ): List<Long> {
        val out = ArrayList<Long>()
        for (t in from..to) while (poll(t) != null) out += t
        return out
    }

    @Test
    fun `a burst of pictures leaves evenly, one frame interval apart, after the latency`() {
        val pacer = FramePacer(latencyMillis = 500)
        // Twelve pictures that all arrived within 55 ms of each other at t = 1000.
        pacer.offerGop(0, (0 until 12).map { 1_000L + it * 5 })
        assertThat(pacer.size).isEqualTo(12)
        assertThat(pacer.poll(1_499)).isNull()
        val releases = pacer.releasesBetween(1_000, 3_000)
        assertThat(releases).hasSize(12)
        assertThat(releases.first()).isEqualTo(1_500)
        val gaps = releases.zipWithNext { a, b -> b - a }
        assertThat(gaps.distinct()).containsExactly(83L, 84L)
        assertThat(pacer.rebuffers).isEqualTo(0)
        assertThat(pacer.catchUps).isEqualTo(0)
    }

    @Test
    fun `arrival jitter inside the latency does not move the release times`() {
        val pacer = FramePacer(latencyMillis = 500)
        // The same stream, arriving with ±200 ms of jitter around its nominal times.
        val jitter = listOf(0L, 180, -150, 200, -200, 90, -90, 199, -199, 50, -50, 0)
        val arrivals = (0 until 12).map { i -> 1_000L + i * 83 + jitter[i] }
        arrivals.forEachIndexed { i, at -> pacer.offer(unit(i * frameMicros, keyframe = i == 0), at) }
        val releases = pacer.releasesBetween(1_000, 3_000)
        assertThat(releases.first()).isEqualTo(1_500)
        assertThat(releases.zipWithNext { a, b -> b - a }.distinct()).containsExactly(83L, 84L)
        assertThat(pacer.rebuffers).isEqualTo(0)
    }

    @Test
    fun `a picture arriving after it was due re-buffers once and the tile pauses for the latency`() {
        val pacer = FramePacer(latencyMillis = 500)
        pacer.offer(unit(0, keyframe = true), 1_000)
        pacer.offer(unit(frameMicros), 1_083)
        assertThat(pacer.releasesBetween(1_000, 1_600)).containsExactly(1_500L, 1_583L)
        // The relay stalls: the third picture (due 1 666) arrives at 2 400.
        pacer.offer(unit(2 * frameMicros), 2_400)
        assertThat(pacer.rebuffers).isEqualTo(1)
        assertThat(pacer.poll(2_899)).isNull()
        assertThat(pacer.poll(2_900)).isNotNull()
        // And the stream is evenly paced again from the new mapping.
        pacer.offer(unit(3 * frameMicros), 2_410)
        assertThat(pacer.nextDueMillis()).isEqualTo(2_983)
    }

    @Test
    fun `a backlog of more than two GOPs is dropped to the newest keyframe and the rest keeps its spacing`() {
        val pacer = FramePacer(latencyMillis = 500, catchUpMillis = 2_000)
        // Three GOPs arrive in a burst at t = 1000 (a reconnect flushed them): 36 pictures, 3 s of stream.
        pacer.offerGop(0, List(12) { 1_000L })
        pacer.offerGop(12 * frameMicros, List(12) { 1_001L })
        // Two GOPs wait at most latency + 2 s: not yet a backlog.
        assertThat(pacer.catchUps).isEqualTo(0)
        pacer.offerGop(24 * frameMicros, List(12) { 1_002L })
        assertThat(pacer.catchUps).isEqualTo(1)
        // Everything before the third keyframe is gone; the third GOP plays, evenly, well inside the bound.
        assertThat(pacer.dropped).isEqualTo(24)
        assertThat(pacer.size).isEqualTo(12)
        val releases = pacer.releasesBetween(1_002, 1_002 + 500 + 2_000)
        assertThat(releases).hasSize(12)
        assertThat(releases.zipWithNext { a, b -> b - a }.distinct()).containsExactly(83L, 84L)
        assertThat(pacer.rebuffers).isEqualTo(0)
        // The next picture of the stream follows the kept ones at the normal spacing.
        pacer.offer(unit(36 * frameMicros), releases.last())
        assertThat(pacer.nextDueMillis()!! - releases.last()).isIn(83L..84L)
    }

    @Test
    fun `without a keyframe in the backlog nothing is dropped and the backlog is played out fast`() {
        val pacer = FramePacer(latencyMillis = 500, catchUpMillis = 2_000)
        pacer.offer(unit(0, keyframe = true), 1_000)
        (1 until 30).forEach { pacer.offer(unit(it * frameMicros), 1_000) }
        assertThat(pacer.catchUps).isEqualTo(1)
        assertThat(pacer.dropped).isEqualTo(0)
        assertThat(pacer.size).isEqualTo(30)
        // A good part of the backlog is due at once, and all of it well before the 2.9 s it spanned.
        val atOnce = pacer.releasesBetween(1_000, 1_000).size
        assertThat(atOnce).isAtLeast(15)
        assertThat(pacer.releasesBetween(1_001, 1_000 + 500 + 400)).hasSize(30 - atOnce)
    }

    @Test
    fun `the capacity bound drops the oldest picture`() {
        val pacer = FramePacer(latencyMillis = 60_000, capacity = 3)
        (0 until 5).forEach { pacer.offer(unit(it * frameMicros, keyframe = it == 0), 0) }
        assertThat(pacer.size).isEqualTo(3)
        assertThat(pacer.dropped).isEqualTo(2)
    }

    @Test
    fun `pictures without a timestamp go straight through, in order`() {
        val pacer = FramePacer(latencyMillis = 500)
        pacer.offer(unit(null, keyframe = true), 1_000)
        assertThat(pacer.poll(1_000)).isNotNull()
        pacer.offer(unit(0, keyframe = true), 1_000)
        pacer.offer(unit(null), 1_001)
        // The untimed picture never jumps the queue in front of a timed one.
        assertThat(pacer.poll(1_001)).isNull()
        assertThat(pacer.releasesBetween(1_001, 1_600)).containsExactly(1_500L, 1_500L)
    }

    @Test
    fun `a timestamp that jumps backwards is a new stream and re-maps the clock`() {
        val pacer = FramePacer(latencyMillis = 500)
        pacer.offer(unit(90_000_000, keyframe = true), 1_000)
        pacer.offer(unit(0, keyframe = true), 1_100)
        assertThat(pacer.releasesBetween(1_000, 2_000)).containsExactly(1_500L, 1_600L)
    }

    @Test
    fun `overdue counts only pictures past their time and clear forgets everything`() {
        val pacer = FramePacer(latencyMillis = 500)
        pacer.offerGop(0, List(12) { 1_000L })
        assertThat(pacer.overdue(1_000)).isEqualTo(0)
        assertThat(pacer.overdue(1_700)).isEqualTo(3)
        pacer.clear()
        assertThat(pacer.size).isEqualTo(0)
        assertThat(pacer.nextDueMillis()).isNull()
        // A new mapping, not a late picture against the old one.
        pacer.offer(unit(0, keyframe = true), 5_000)
        assertThat(pacer.rebuffers).isEqualTo(0)
        assertThat(pacer.nextDueMillis()).isEqualTo(5_500)
    }
}
