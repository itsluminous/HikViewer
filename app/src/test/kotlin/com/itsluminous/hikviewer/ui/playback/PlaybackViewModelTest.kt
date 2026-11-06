// kotlinx-coroutines-test's dispatcher and time-control API is still marked experimental.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.itsluminous.hikviewer.ui.playback

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.playback.RecordSegment
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.SyntheticStream
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.TileMode
import com.itsluminous.hikviewer.player.TileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PlaybackViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val cloud =
        FakeCloudServices(cameraCount = 4, signalCount = 4).apply {
            playbackPaced = false
            playbackHold = true
            playbackFramesPerWindow = 24 // two seconds of pictures per window, delivered at once
        }
    private val decoders = RecordingDecoderFactory()
    private lateinit var graph: AppGraph
    private lateinit var camera: Camera
    private val today = LocalDate.of(2026, 11, 4)
    private val yesterday = today.minusDays(1)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        graph =
            TestGraph.create(
                ApplicationProvider.getApplicationContext(),
                cloud,
                decoders = decoders,
                playbackDispatcher = dispatcher,
            )
        kotlinx.coroutines.runBlocking {
            graph.account.login("a", "secret", Region.INDIA)
            graph.account.loadInventory()
        }
        graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        camera =
            graph.account.inventory.value!!
                .cameras[1]
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PlaybackViewModel(
        camera,
        graph.account,
        graph.playbackFactory,
        today,
        io = dispatcher,
        // The seek debounce and acceleration read this clock; the scheduler's time keeps them in step with delay().
        clock = { dispatcher.scheduler.currentTime },
    )

    private val account get() = cloud.accounts.single()

    private fun run(millis: Long = 0) {
        if (millis > 0) dispatcher.scheduler.advanceTimeBy(millis)
        dispatcher.scheduler.runCurrent()
    }

    private fun started(): PlaybackViewModel {
        val vm = viewModel()
        vm.player.attachSurface(android.view.Surface(android.graphics.SurfaceTexture(1)))
        vm.onStart()
        run()
        return vm
    }

    @Test
    fun `lists today and opens a hardware window five minutes before the latest footage`() {
        val vm = started()

        val state = vm.state.value
        assertThat(account.searched).containsExactly(camera to today)
        assertThat(state.segments).hasSize(2)
        assertThat(state.phase).isEqualTo(PlaybackPhase.PLAYING)
        val (opened, window) = account.playbackOpened.single()
        assertThat(opened).isEqualTo(camera)
        assertThat(window.begin).isEqualTo(today.atTime(23, 55))
        assertThat(window.end).isEqualTo(today.plusDays(1).atStartOfDay())
        assertThat(vm.player.mode).isEqualTo(TileMode.HW_LIVE)
        assertThat(decoders.created.single().codecName).startsWith("fake.hw.")
        // The fake delivers two seconds of pictures at once; the playhead followed their timestamps.
        assertThat(vm.state.value.position).isGreaterThan(today.atTime(23, 55))
        assertThat(vm.state.value.position).isAtMost(today.atTime(23, 55, 2))
    }

    @Test
    fun `the playhead follows presentation times and the next segment opens when a window ends`() {
        cloud.recordings = { day ->
            listOf(
                RecordSegment(day.atTime(10, 0, 0), day.atTime(10, 0, 2), RecordSegment.TYPE_TIMING),
                RecordSegment(day.atTime(10, 0, 10), day.atTime(10, 0, 12), RecordSegment.TYPE_EVENT),
            )
        }
        cloud.playbackHold = false // windows run out, as with the relay
        cloud.playbackFramesPerWindow = null
        val vm = viewModel()
        vm.onStart()
        run()
        vm.changeDay(-1, today) // a past day starts at its first segment
        run()

        val windows = account.playbackOpened.filter { it.first == camera }.map { it.second }
        assertThat(windows.map { it.begin }).containsAtLeast(yesterday.atTime(10, 0, 0), yesterday.atTime(10, 0, 10))
        assertThat(windows.last().end).isEqualTo(yesterday.atTime(10, 0, 12))
        // Both windows played out (the fake is unpaced); nothing follows → ended at the last picture.
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.ENDED)
        assertThat(vm.state.value.position).isAtLeast(yesterday.atTime(10, 0, 10))
        assertThat(vm.state.value.position).isAtMost(yesterday.atTime(10, 0, 12))
        assertThat(vm.player.state.value).isNotInstanceOf(TileState.Error::class.java)
    }

    @Test
    fun `seek presses move the label at once and reopen once after 600 ms of quiet`() {
        cloud.recordings =
            { day -> listOf(RecordSegment(day.atTime(6, 0), day.atTime(12, 0), RecordSegment.TYPE_TIMING)) }
        val vm = started()
        vm.changeDay(-1, today)
        run()
        assertThat(account.playbackOpened).hasSize(2)
        val before = vm.state.value.position

        // Three quick presses (released in between): +10 s each, nothing reopened while input keeps coming.
        repeat(3) {
            vm.seekBy(+1)
            vm.endSeekHold()
            run(200)
        }
        assertThat(vm.state.value.position).isEqualTo(before.plusSeconds(30))
        assertThat(account.playbackOpened).hasSize(2)
        run(SeekController.DEBOUNCE_MILLIS - 201) // 599 ms since the last press: not yet
        assertThat(account.playbackOpened).hasSize(2)
        run(1) // 600 ms: reopened at the target
        assertThat(account.playbackOpened).hasSize(3)
        assertThat(
            account.playbackOpened
                .last()
                .second.begin,
        ).isEqualTo(before.plusSeconds(30))

        // A tap on the bar is debounced the same way; a drag's samples collapse into one reopen.
        vm.seekToFraction(0.25f)
        run(300)
        vm.seekToFraction(0.3f)
        run(300)
        vm.seekToFraction(0.35f)
        assertThat(vm.state.value.position).isEqualTo(yesterday.atTime(8, 24))
        assertThat(account.playbackOpened).hasSize(3)
        run(SeekController.DEBOUNCE_MILLIS)
        assertThat(account.playbackOpened).hasSize(4)
        assertThat(
            account.playbackOpened
                .last()
                .second.begin,
        ).isEqualTo(yesterday.atTime(8, 24))

        vm.seekToFraction(0.5f) // noon is the end of the footage: the playhead stops there, nothing left to open
        run(SeekController.DEBOUNCE_MILLIS)
        assertThat(vm.state.value.position).isEqualTo(yesterday.atTime(12, 0))
        assertThat(account.playbackOpened).hasSize(4)
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.ENDED)
    }

    @Test
    fun `a held seek key accelerates on the schedule, clamps to the footage and resets on release`() {
        cloud.recordings =
            { day -> listOf(RecordSegment(day.atTime(6, 0), day.atTime(12, 0), RecordSegment.TYPE_TIMING)) }
        val vm = started()
        vm.changeDay(-1, today)
        run()
        vm.seekToFraction(PlaybackTimeline.fractionOfDay(yesterday.atTime(9, 0)))
        run(SeekController.DEBOUNCE_MILLIS)
        val opens = account.playbackOpened.size
        var at = vm.state.value.position // 09:00 plus the two seconds of pictures the fake delivered at once
        val start = at

        // Key down, then auto-repeats every 100 ms: 10 s steps for the first second, 30 s to 2.5 s, then 1 min.
        fun repeatFor(millis: Long): List<Long> {
            val jumps = mutableListOf<Long>()
            var elapsed = 0L
            while (elapsed < millis) {
                vm.seekBy(+1)
                val next = vm.state.value.position
                jumps +=
                    java.time.Duration
                        .between(at, next)
                        .seconds
                at = next
                run(100)
                elapsed += 100
            }
            return jumps
        }
        assertThat(repeatFor(1_000).distinct()).containsExactly(10L)
        assertThat(repeatFor(1_500).distinct()).containsExactly(30L)
        assertThat(repeatFor(1_000).distinct()).containsExactly(60L)
        assertThat(account.playbackOpened).hasSize(opens) // the key never rested: no reopen yet
        val label = vm.state.value.position
        assertThat(label).isEqualTo(start.plusSeconds(100 + 15 * 30 + 10 * 60))

        vm.endSeekHold()
        run(SeekController.DEBOUNCE_MILLIS)
        assertThat(account.playbackOpened).hasSize(opens + 1)
        assertThat(
            account.playbackOpened
                .last()
                .second.begin,
        ).isEqualTo(label)

        // The next hold starts small again; seeking past the footage stops at its edges.
        val resumed = vm.state.value.position // the reopened window's first pictures moved it on a little
        vm.seekBy(-1)
        assertThat(vm.state.value.position).isEqualTo(resumed.minusSeconds(10))
        vm.endSeekHold()
        vm.seekToFraction(PlaybackTimeline.fractionOfDay(yesterday.atTime(11, 59, 50)))
        run(SeekController.DEBOUNCE_MILLIS)
        vm.seekBy(+1) // +10 s would pass noon: clamped to the end of the footage
        assertThat(vm.state.value.position).isEqualTo(yesterday.atTime(12, 0))
        run(6_000) // still held (no release): six seconds on, the step is 15 min
        repeat(30) { vm.seekBy(-1) } // 7.5 h back: clamped to the first recording
        assertThat(vm.state.value.position).isEqualTo(yesterday.atTime(6, 0))
        vm.endSeekHold()
        vm.onStop()
    }

    @Test
    fun `pictures arriving between a seek step and its commit do not move the playhead back`() {
        cloud.recordings =
            { day -> listOf(RecordSegment(day.atTime(6, 0), day.atTime(12, 0), RecordSegment.TYPE_TIMING)) }
        cloud.playbackPaced = true // pictures keep coming while the key is held
        cloud.playbackFramesPerWindow = null
        val vm = started()
        run(SyntheticStream.FRAME_PERIOD_MILLIS * 6)
        val playing = vm.state.value.position
        assertThat(playing).isGreaterThan(today.atTime(11, 55))
        vm.seekBy(+1) // DOWN: +10 s
        val sought = vm.state.value.position
        assertThat(sought).isEqualTo(PlaybackTimeline.shift(today, playing, 10))
        run(SyntheticStream.FRAME_PERIOD_MILLIS * 3) // the stream delivers three more pictures
        assertThat(vm.state.value.position).isEqualTo(sought)
        vm.endSeekHold() // UP
        run(SeekController.DEBOUNCE_MILLIS)
        assertThat(
            account.playbackOpened
                .last()
                .second.begin,
        ).isEqualTo(sought)
        // After the commit the playhead follows the new window's pictures again.
        run(SyntheticStream.FRAME_PERIOD_MILLIS * 6)
        assertThat(vm.state.value.position).isAtLeast(sought)
        vm.onStop()
    }

    @Test
    fun `pause keeps the playhead and resume reopens there`() {
        cloud.recordings =
            { day -> listOf(RecordSegment(day.atTime(6, 0), day.atTime(12, 0), RecordSegment.TYPE_TIMING)) }
        val vm = started()
        vm.changeDay(-1, today)
        run()
        vm.seekBy(+1)
        vm.endSeekHold()
        run(SeekController.DEBOUNCE_MILLIS)
        val opens = account.playbackOpened.size
        val playhead = vm.state.value.position

        vm.togglePause()
        run(5_000)
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.PAUSED)
        assertThat(account.playbackOpened).hasSize(opens)
        vm.seekBy(+1)
        vm.endSeekHold()
        run(SeekController.DEBOUNCE_MILLIS + 1)
        assertThat(account.playbackOpened).hasSize(opens) // seeking while paused only moves the playhead
        assertThat(vm.state.value.position).isEqualTo(playhead.plusSeconds(10))

        vm.togglePause()
        run()
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.PLAYING)
        assertThat(account.playbackOpened).hasSize(opens + 1)
        assertThat(
            account.playbackOpened
                .last()
                .second.begin,
        ).isEqualTo(playhead.plusSeconds(10))
    }

    @Test
    fun `other speeds step one keyframe a second, the speed apart, and stop at the edge of the footage`() {
        cloud.recordings =
            { day -> listOf(RecordSegment(day.atTime(6, 0), day.atTime(6, 1), RecordSegment.TYPE_TIMING)) }
        val vm = started()
        vm.changeDay(-1, today)
        run()
        vm.togglePause()
        vm.seekToFraction(PlaybackTimeline.fractionOfDay(yesterday.atTime(6, 0, 30)))
        vm.togglePause()
        run()
        val opens = account.playbackOpened.size
        val start = vm.state.value.position // 06:00:30 plus the two seconds of pictures already shown
        assertThat(start).isAtLeast(yesterday.atTime(6, 0, 30))
        assertThat(start).isAtMost(yesterday.atTime(6, 0, 32))

        vm.setSpeed(PlaybackSpeed.FORWARD_10)
        run()
        assertThat(vm.state.value.speed).isEqualTo(PlaybackSpeed.FORWARD_10)
        // The stepper opened the first step's window and the next one's ahead of time, a GOP each.
        val windows = account.playbackOpened.drop(opens).map { it.second }
        assertThat(windows.map { it.begin }).containsExactly(start, start.plusSeconds(10)).inOrder()
        assertThat(windows.first().duration).isEqualTo(PlaybackStepper.WINDOW)
        assertThat(vm.state.value.position).isEqualTo(start)
        run(1_000)
        assertThat(vm.state.value.position).isEqualTo(start.plusSeconds(10))
        run(1_000)
        assertThat(vm.state.value.position).isEqualTo(start.plusSeconds(20))
        run(1_000) // +30 s is past the segment's end → ended
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.ENDED)
        val opensAtEnd = account.playbackOpened.size

        vm.setSpeed(PlaybackSpeed.REVERSE_2) // changing speed while ended does not restart
        run()
        assertThat(account.playbackOpened).hasSize(opensAtEnd)
        vm.togglePause() // OK after the end resumes in the new direction
        run()
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.PLAYING)
        val back = vm.state.value.position
        run(1_000)
        assertThat(vm.state.value.position).isEqualTo(back.minusSeconds(2))
        // Back to 1x plays continuously from the current step.
        vm.setSpeed(PlaybackSpeed.NORMAL)
        run()
        assertThat(
            account.playbackOpened
                .last()
                .second.end,
        ).isEqualTo(yesterday.atTime(6, 1))
    }

    @Test
    fun `days change only into the past, an empty day and a failed search are reported`() {
        val vm = started()
        vm.changeDay(+1, today)
        run()
        assertThat(vm.state.value.day).isEqualTo(today)
        assertThat(account.searched).hasSize(1)

        cloud.recordings = { emptyList() }
        vm.changeDay(-1, today)
        run()
        assertThat(vm.state.value.day).isEqualTo(yesterday)
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.NO_RECORDINGS)
        vm.togglePause() // nothing to play
        vm.commitSeek()
        run()
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.NO_RECORDINGS)

        cloud.nextSearchError = IOException("boom")
        vm.changeDay(-1, today)
        run()
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.ERROR)
        assertThat(vm.state.value.day).isEqualTo(today.minusDays(2))
    }

    @Test
    fun `a seek at 1x and a change between 1x and a fast speed flush the one decoder, steps never touch it`() {
        cloud.recordings =
            { day -> listOf(RecordSegment(day.atTime(6, 0), day.atTime(12, 0), RecordSegment.TYPE_TIMING)) }
        val vm = started()
        vm.changeDay(-1, today) // a day change stops the player for good: a second decoder is built
        run()
        assertThat(decoders.created).hasSize(2)
        assertThat(decoders.created[0].released).isTrue()
        val d = decoders.created[1]
        vm.seekToFraction(PlaybackTimeline.fractionOfDay(yesterday.atTime(8, 0))) // room to step backwards
        run(SeekController.DEBOUNCE_MILLIS)
        assertThat(d.flushes).isEqualTo(1)
        val fedBefore = d.fed.size

        vm.seekBy(+1)
        vm.endSeekHold()
        run(SeekController.DEBOUNCE_MILLIS)
        assertThat(decoders.created).hasSize(2)
        assertThat(d.flushes).isEqualTo(2)
        assertThat(d.released).isFalse()
        assertThat(d.fed.size).isGreaterThan(fedBefore) // the new window's pictures reach the same decoder
        assertThat(d.keyframesOnlySet).isFalse()

        vm.setSpeed(PlaybackSpeed.FORWARD_5) // 1x → stepping: one reopen, then one stream for every step
        run()
        assertThat(decoders.created).hasSize(2)
        assertThat(d.flushes).isEqualTo(3)
        assertThat(d.keyframesOnlySet).isTrue()
        d.emitFirstFrame()
        assertThat(vm.player.state.value).isEqualTo(TileState.Live)
        val opens = account.playbackOpened.size
        repeat(5) {
            run(1_000)
            assertThat(vm.player.state.value).isEqualTo(TileState.Live) // no spinner between two steps
        }
        assertThat(account.playbackOpened.size).isGreaterThan(opens) // the stepper opened windows meanwhile
        assertThat(d.flushes).isEqualTo(3)

        vm.setSpeed(PlaybackSpeed.REVERSE_10) // fast → fast turns the stepper around in place
        run(2_000)
        assertThat(d.flushes).isEqualTo(3)
        assertThat(vm.player.state.value).isEqualTo(TileState.Live)

        vm.seekBy(-1) // a seek while stepping restarts the stepper, not the stream
        vm.endSeekHold()
        run(SeekController.DEBOUNCE_MILLIS + 1_000)
        assertThat(d.flushes).isEqualTo(3)
        assertThat(vm.player.state.value).isEqualTo(TileState.Live)

        vm.setSpeed(PlaybackSpeed.NORMAL) // stepping → 1x reopens the stream once more
        run()
        assertThat(d.flushes).isEqualTo(4)
        assertThat(d.keyframesOnlySet).isFalse()

        vm.onStop() // the screen goes away: now the decoder is released
        assertThat(d.released).isTrue()
    }

    @Test
    fun `fast speeds feed keyframes only, in the direction of travel, with presentation times that only grow`() {
        cloud.recordings =
            { day -> listOf(RecordSegment(day.atTime(6, 0), day.atTime(12, 0), RecordSegment.TYPE_TIMING)) }
        val vm = started()
        vm.changeDay(-1, today)
        run()
        vm.seekToFraction(PlaybackTimeline.fractionOfDay(yesterday.atTime(8, 0))) // room to step backwards
        run(SeekController.DEBOUNCE_MILLIS)
        val d = decoders.created.last()
        assertThat(d.fed.any { !it.isKeyframe }).isTrue()

        vm.setSpeed(PlaybackSpeed.REVERSE_10)
        run()
        assertThat(vm.player.keyframesOnly).isTrue()
        assertThat(d.keyframesOnlySet).isTrue()
        val fedAtReverse = d.fed.size
        val positions = ArrayList<java.time.LocalDateTime>()
        repeat(4) {
            run(1_000)
            positions += vm.state.value.position
        }
        val reverseFed = d.fed.drop(fedAtReverse)
        assertThat(reverseFed.size).isAtLeast(3)
        assertThat(reverseFed.all { it.isKeyframe && it.config != null }).isTrue()
        assertThat(reverseFed.map { it.presentationTimeUs!! }).isInStrictOrder()
        assertThat(positions.reversed()).isInStrictOrder() // 10 s back per second
        assertThat(positions.first().minusSeconds(10)).isEqualTo(positions[1])

        vm.setSpeed(PlaybackSpeed.FORWARD_10)
        run()
        assertThat(vm.player.keyframesOnly).isTrue()
        val turned = vm.state.value.position
        run(1_000)
        assertThat(vm.state.value.position).isEqualTo(turned.plusSeconds(10))
        assertThat(d.fed.drop(fedAtReverse).all { it.isKeyframe }).isTrue()

        vm.setSpeed(PlaybackSpeed.NORMAL)
        run()
        assertThat(vm.player.keyframesOnly).isFalse()
        vm.onStop()
    }

    @Test
    fun `controls cycle and stop releases the stream while keeping the phase`() {
        val vm = started()
        assertThat(vm.state.value.control).isEqualTo(PlaybackControl.BAR)
        vm.moveControl(+1)
        assertThat(vm.state.value.control).isEqualTo(PlaybackControl.DATE)
        vm.moveControl(+1)
        assertThat(vm.state.value.control).isEqualTo(PlaybackControl.SPEED)
        vm.moveControl(+1)
        assertThat(vm.state.value.control).isEqualTo(PlaybackControl.BAR)
        vm.moveControl(-1)
        assertThat(vm.state.value.control).isEqualTo(PlaybackControl.SPEED)

        vm.cycleSpeed()
        assertThat(vm.state.value.speed).isEqualTo(PlaybackSpeed.FORWARD_2)
        vm.cycleSpeed(forward = false)
        vm.cycleSpeed(forward = false)
        assertThat(vm.state.value.speed).isEqualTo(PlaybackSpeed.REVERSE_2)

        val opens = account.playbackOpened.size
        vm.onStop()
        run(10_000)
        assertThat(account.playbackOpened).hasSize(opens)
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.PLAYING)
        vm.onStart()
        run()
        // -2x resumes as stepping: the first step's window and the next one's open at once.
        assertThat(account.playbackOpened).hasSize(opens + PlaybackStepper.PREFETCH_STEPS)
        assertThat(decoders.created.first().released).isTrue()
    }
}
