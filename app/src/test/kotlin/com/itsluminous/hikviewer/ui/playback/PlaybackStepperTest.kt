// kotlinx-coroutines-test's dispatcher and time-control API is still marked experimental.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.itsluminous.hikviewer.ui.playback

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.playback.PlaybackWindow
import com.itsluminous.hikviewer.core.playback.RecordSegment
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.fakes.SyntheticStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * [PlaybackStepper] against a fake relay: windows that answer after a time-to-first-picture and
 * then deliver one keyframe per second of footage at real time (as the cloud does), or all at
 * once (a relay faster than real time). Every keyframe's bytes encode its recorder time so the
 * pictures the stepper presents can be read back.
 */
class PlaybackStepperTest {
    private val day = LocalDate.of(2026, 11, 4)
    private val segments = listOf(RecordSegment(day.atTime(6, 0), day.atTime(7, 0), RecordSegment.TYPE_TIMING))
    private val dispatcher = StandardTestDispatcher()

    /** The fake relay. */
    private inner class Relay(
        /** Wall time from open to the first keyframe. */
        var ttffMillis: Long = 400,
        /** Keyframes arrive one per second of footage (true) or all at once (false). */
        var realTime: Boolean = true,
        /** Opens that fail with an I/O error before any picture. */
        var failures: Int = 0,
    ) {
        val opened = ArrayList<PlaybackWindow>()
        var active = 0
        var peakActive = 0

        fun open(window: PlaybackWindow): Flow<AccessUnit> = flow {
            opened += window
            active++
            peakActive = maxOf(peakActive, active)
            try {
                if (failures > 0) {
                    failures--
                    throw IOException("relay busy")
                }
                delay(ttffMillis)
                var t = window.begin
                var i = 0L
                while (t.isBefore(window.end)) {
                    emit(keyframe(t, i * MICROS_PER_SECOND))
                    i++
                    t = t.plusSeconds(1)
                    if (realTime) delay(1_000)
                }
            } finally {
                active--
            }
        }
    }

    private val presented = ArrayList<AccessUnit>()
    private val positions = ArrayList<LocalDateTime>()
    private var ended = 0

    private fun TestScope.stepper(
        relay: Relay,
        window: Duration = PlaybackStepper.WINDOW,
    ): PlaybackStepper {
        val s =
            PlaybackStepper(
                segments,
                relay::open,
                this,
                onPosition = { positions += it },
                onEnded = { ended++ },
                window = window,
            )
        backgroundScope.launch { s.frames.collect { presented += it } }
        return s
    }

    private fun run(millis: Long = 0) {
        if (millis > 0) dispatcher.scheduler.advanceTimeBy(millis)
        dispatcher.scheduler.runCurrent()
    }

    private fun shownTimes() = presented.map(::timeOf)

    @Test
    fun `forward shows one keyframe a second, factor seconds apart, from windows opened ahead`() = runTest(dispatcher) {
        val relay = Relay()
        val s = stepper(relay)
        s.start(day.atTime(6, 10), 10)
        run()
        // The first step is due at once; its picture is 400 ms away, so the label moves and the picture follows.
        assertThat(positions).containsExactly(day.atTime(6, 10))
        assertThat(presented).isEmpty()
        assertThat(relay.opened.map { it.begin }).containsExactly(day.atTime(6, 10), day.atTime(6, 10, 10)).inOrder()
        run(400)
        assertThat(shownTimes()).containsExactly(day.atTime(6, 10))
        run(600)
        assertThat(shownTimes()).containsExactly(day.atTime(6, 10), day.atTime(6, 10, 10)).inOrder()
        run(3_000)
        assertThat(shownTimes()).isEqualTo((0..4).map { day.atTime(6, 10).plusSeconds(10L * it) })
        assertThat(positions.last()).isEqualTo(day.atTime(6, 10, 40))
        // Synthetic presentation times count up whatever the footage does.
        assertThat(presented.map { it.presentationTimeUs }).isInStrictOrder()
        assertThat(presented.all { it.isKeyframe && it.config != null }).isTrue()
        // Two windows at a time at most, each closed once it has delivered its step; the sixth
        // step's window and the seventh's are open now, ahead of their time.
        assertThat(relay.peakActive).isAtMost(PlaybackStepper.MAX_FETCHES)
        assertThat(relay.opened).hasSize(7)
        s.stop()
    }

    @Test
    fun `reverse walks backwards through keyframes in order`() = runTest(dispatcher) {
        val relay = Relay()
        val s = stepper(relay)
        s.start(day.atTime(6, 30), -10)
        run(4_400)
        assertThat(shownTimes()).isEqualTo((0..4).map { day.atTime(6, 30).minusSeconds(10L * it) })
        assertThat(positions.first()).isEqualTo(day.atTime(6, 30))
        assertThat(positions.last()).isEqualTo(day.atTime(6, 29, 20))
        // Each reverse step asks for the GOP at its own time, going backwards.
        assertThat(relay.opened.take(3).map { it.begin })
            .containsExactly(day.atTime(6, 30), day.atTime(6, 29, 50), day.atTime(6, 29, 40))
            .inOrder()
        s.stop()
    }

    @Test
    fun `a late picture keeps the label moving and is shown when it arrives`() = runTest(dispatcher) {
        val relay = Relay(ttffMillis = 1_500)
        val s = stepper(relay)
        s.start(day.atTime(6, 10), 2)
        run()
        run(1_000) // second step due: nothing has arrived, the label still moves
        assertThat(presented).isEmpty()
        assertThat(positions).containsExactly(day.atTime(6, 10), day.atTime(6, 10, 2)).inOrder()
        run(500) // the first window answers, but its picture is a whole stride behind the step that is waiting
        assertThat(presented).isEmpty()
        assertThat(positions.last()).isEqualTo(day.atTime(6, 10, 2))
        run(500) // the third step's window (opened at the start too) is on time
        assertThat(shownTimes()).containsExactly(day.atTime(6, 10, 4))
        assertThat(positions.last()).isEqualTo(day.atTime(6, 10, 4))
        assertThat(s.describe()).contains("late=2")
        run(1_000) // 06:10:06 is still on its way; the keyframe a second before it stands in, labelled as itself
        assertThat(shownTimes().last()).isEqualTo(day.atTime(6, 10, 5))
        assertThat(positions.last()).isEqualTo(day.atTime(6, 10, 5))
        s.stop()
    }

    @Test
    fun `a relay faster than real time serves many steps from one window, reopened before it runs out`() =
        runTest(dispatcher) {
            val relay = Relay(realTime = false)
            val s = stepper(relay, window = Duration.ofSeconds(60))
            s.start(day.atTime(6, 10), 10)
            run(400)
            assertThat(relay.opened).hasSize(1)
            run(3_000) // steps 06:10:10 … 06:10:30 all come out of the ring
            assertThat(shownTimes()).isEqualTo((0..3).map { day.atTime(6, 10).plusSeconds(10L * it) })
            assertThat(relay.opened).hasSize(1)
            // The window was closed once it had delivered every step it covers; its tail is not asked for.
            assertThat(s.describe()).contains("ring=51 fetches=0")
            run(1_000) // 06:10:40 shown; 06:11:00 is now two steps away and beyond the window → it opens ahead of time
            assertThat(shownTimes().last()).isEqualTo(day.atTime(6, 10, 40))
            assertThat(relay.opened).hasSize(2)
            assertThat(relay.opened[1].begin).isEqualTo(day.atTime(6, 11))
            run(2_000)
            assertThat(shownTimes().takeLast(2)).containsExactly(day.atTime(6, 10, 50), day.atTime(6, 11)).inOrder()
            s.stop()
        }

    @Test
    fun `a seek forgets the ring and starts over at the new time`() = runTest(dispatcher) {
        val relay = Relay(realTime = false)
        val s = stepper(relay, window = Duration.ofSeconds(60))
        s.start(day.atTime(6, 10), 10)
        run(2_400)
        assertThat(s.describe()).contains("ring=51")
        s.seekTo(day.atTime(6, 40))
        run()
        assertThat(s.describe()).contains("ring=0")
        assertThat(relay.opened.last().begin).isEqualTo(day.atTime(6, 40))
        run(400)
        assertThat(shownTimes().last()).isEqualTo(day.atTime(6, 40))
        run(1_000)
        assertThat(shownTimes().last()).isEqualTo(day.atTime(6, 40, 10))
        s.stop()
    }

    @Test
    fun `pause stops the walk and the windows until the next seek`() = runTest(dispatcher) {
        val relay = Relay()
        val s = stepper(relay)
        s.start(day.atTime(6, 10), 10)
        run(1_400)
        val shown = presented.size
        s.pause()
        run(5_000)
        assertThat(presented).hasSize(shown)
        assertThat(relay.active).isEqualTo(0)
        s.seekTo(day.atTime(6, 20))
        run(400)
        assertThat(shownTimes().last()).isEqualTo(day.atTime(6, 20))
        s.stop()
    }

    @Test
    fun `a speed change turns around in place and reuses the keyframes already fetched`() = runTest(dispatcher) {
        val relay = Relay(realTime = false)
        val s = stepper(relay, window = Duration.ofSeconds(60))
        s.start(day.atTime(6, 10), 10)
        run(3_400) // 06:10:00 … 06:10:30 shown
        val opens = relay.opened.size
        s.setSpeed(-10) // from the picture on screen (06:10:30) one new stride back
        run(1_000)
        assertThat(shownTimes().last()).isEqualTo(day.atTime(6, 10, 20))
        assertThat(relay.opened).hasSize(opens) // the ring had it, and the next one
        run(1_000)
        assertThat(shownTimes().last()).isEqualTo(day.atTime(6, 10, 10))
        assertThat(relay.opened).hasSize(opens + 1) // 06:09:50 is two steps ahead now and not in the ring
        assertThat(relay.opened.last().begin).isEqualTo(day.atTime(6, 9, 50))
        s.setSpeed(-2)
        run(1_000)
        assertThat(shownTimes().last()).isEqualTo(day.atTime(6, 10, 8))
        s.stop()
    }

    @Test
    fun `the footage running out ends the walk`() = runTest(dispatcher) {
        val relay = Relay()
        val s = stepper(relay)
        s.start(day.atTime(6, 59, 40), 10)
        run(400)
        run(1_000)
        run(1_000) // 07:00:00 has no footage
        assertThat(ended).isEqualTo(1)
        assertThat(shownTimes()).containsExactly(day.atTime(6, 59, 40), day.atTime(6, 59, 50)).inOrder()
        val shown = presented.size
        run(5_000)
        assertThat(presented).hasSize(shown)
        assertThat(ended).isEqualTo(1)
    }

    @Test
    fun `a window that fails costs its step and nothing else`() = runTest(dispatcher) {
        val relay = Relay(failures = 1)
        val s = stepper(relay)
        s.start(day.atTime(6, 10), 10)
        run()
        assertThat(relay.opened.map { it.begin }).containsExactly(day.atTime(6, 10), day.atTime(6, 10, 10)).inOrder()
        run(1_400)
        // 06:10:00 is never shown (no immediate retry: a relay that refuses would be hammered); the walk goes on.
        assertThat(s.describe()).contains("failed=1")
        assertThat(shownTimes()).containsExactly(day.atTime(6, 10, 10))
        assertThat(relay.opened.map { it.begin }).contains(day.atTime(6, 10, 20))
        s.stop()
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L

        private val keyframeTemplate: AccessUnit by lazy {
            SyntheticStream.pictures.first { it.isKeyframe && it.config != null }
        }

        /** A keyframe whose bytes are the recorder time, so a presented picture can be read back. */
        fun keyframe(
            time: LocalDateTime,
            pts: Long,
        ): AccessUnit {
            val data = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(time.toEpochSecond(ZoneOffset.UTC)).array()
            return AccessUnit(keyframeTemplate.codec, data, pts, isKeyframe = true, config = keyframeTemplate.config)
        }

        fun timeOf(unit: AccessUnit): LocalDateTime =
            LocalDateTime.ofEpochSecond(ByteBuffer.wrap(unit.data).long, 0, ZoneOffset.UTC)
    }
}
