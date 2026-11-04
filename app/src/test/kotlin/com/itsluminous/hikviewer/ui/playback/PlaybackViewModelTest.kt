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
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.TileMode
import com.itsluminous.hikviewer.player.TileState
import kotlinx.coroutines.Dispatchers
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

    private fun viewModel(hopMillis: Long = 2_000) =
        PlaybackViewModel(camera, graph.account, graph.playbackFactory, today, io = dispatcher, hopMillis = hopMillis)

    private val account get() = cloud.accounts.single()

    private fun run(millis: Long = 0) {
        if (millis > 0) dispatcher.scheduler.advanceTimeBy(millis)
        dispatcher.scheduler.runCurrent()
    }

    private fun started(hopMillis: Long = 2_000): PlaybackViewModel {
        val vm = viewModel(hopMillis)
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
    fun `seek steps accumulate and reopen once after the commit delay or on key release`() {
        cloud.recordings =
            { day -> listOf(RecordSegment(day.atTime(6, 0), day.atTime(12, 0), RecordSegment.TYPE_TIMING)) }
        val vm = started()
        vm.changeDay(-1, today)
        run()
        assertThat(account.playbackOpened).hasSize(2)
        val before = vm.state.value.position

        repeat(3) { vm.seekBy(+1, repeatCount = it) }
        vm.seekBy(+1, repeatCount = PlaybackTimeline.ACCELERATE_AFTER_REPEATS)
        run()
        assertThat(vm.state.value.position).isEqualTo(before.plusSeconds(10 + 10 + 10 + 60))
        assertThat(account.playbackOpened).hasSize(2) // not yet: the key may still be held

        run(PlaybackViewModel.SEEK_COMMIT_MILLIS + 1)
        assertThat(account.playbackOpened).hasSize(3)
        assertThat(
            account.playbackOpened
                .last()
                .second.begin,
        ).isEqualTo(before.plusSeconds(90))

        vm.seekBy(-1)
        val target = vm.state.value.position
        vm.commitSeek()
        run()
        assertThat(account.playbackOpened).hasSize(4)
        assertThat(
            account.playbackOpened
                .last()
                .second.begin,
        ).isEqualTo(target)

        vm.seekToFraction(0.25f) // a tap on the bar seeks at once
        run()
        assertThat(account.playbackOpened).hasSize(5)
        assertThat(
            account.playbackOpened
                .last()
                .second.begin,
        ).isEqualTo(yesterday.atTime(6, 0))
        vm.seekToFraction(0.5f) // noon is the end of the footage: nothing left to open
        run()
        assertThat(account.playbackOpened).hasSize(5)
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.ENDED)
    }

    @Test
    fun `pause keeps the playhead and resume reopens there`() {
        cloud.recordings =
            { day -> listOf(RecordSegment(day.atTime(6, 0), day.atTime(12, 0), RecordSegment.TYPE_TIMING)) }
        val vm = started()
        vm.changeDay(-1, today)
        run()
        vm.seekBy(+1)
        vm.commitSeek()
        run()
        val opens = account.playbackOpened.size
        val playhead = vm.state.value.position

        vm.togglePause()
        run(5_000)
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.PAUSED)
        assertThat(account.playbackOpened).hasSize(opens)
        vm.seekBy(+1)
        run(PlaybackViewModel.SEEK_COMMIT_MILLIS + 1)
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
    fun `other speeds hop by factor times the period and stop at the edge of the footage`() {
        cloud.recordings =
            { day -> listOf(RecordSegment(day.atTime(6, 0), day.atTime(6, 1), RecordSegment.TYPE_TIMING)) }
        val vm = started(hopMillis = 2_000)
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
        val first = account.playbackOpened.last().second
        assertThat(first.begin).isEqualTo(start)
        assertThat(first.duration).isEqualTo(PlaybackViewModel.HOP_WINDOW)
        run(2_000)
        assertThat(
            account.playbackOpened
                .last()
                .second.begin,
        ).isEqualTo(start.plusSeconds(20))
        run(2_000) // +40 s is past the segment's end → ended
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.ENDED)
        assertThat(account.playbackOpened).hasSize(opens + 2)

        vm.setSpeed(PlaybackSpeed.REVERSE_2) // changing speed while ended does not restart
        run()
        assertThat(account.playbackOpened).hasSize(opens + 2)
        vm.togglePause() // OK after the end resumes in the new direction
        run()
        assertThat(vm.state.value.phase).isEqualTo(PlaybackPhase.PLAYING)
        val back =
            account.playbackOpened
                .last()
                .second.begin
        run(2_000)
        assertThat(
            account.playbackOpened
                .last()
                .second.begin,
        ).isEqualTo(back.minusSeconds(4))
        // Back to 1x plays continuously from the current hop.
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
        assertThat(account.playbackOpened).hasSize(opens + 1)
        assertThat(decoders.created.first().released).isTrue()
    }
}
