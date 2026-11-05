package com.itsluminous.hikviewer.ui.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.playback.PlaybackPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PhoneCameraViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val cloud =
        FakeCloudServices(cameraCount = 4, signalCount = 3).apply {
            playbackPaced = false
            playbackHold = true
            playbackFramesPerWindow = 24
        }
    private lateinit var graph: AppGraph
    private val today = LocalDate.of(2026, 11, 4)
    private val account get() = cloud.accounts.single()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        graph =
            TestGraph.create(
                ApplicationProvider.getApplicationContext(),
                cloud,
                decoders = RecordingDecoderFactory(),
                playbackDispatcher = dispatcher,
            )
        kotlinx.coroutines.runBlocking {
            graph.account.login("a", "secret", Region.INDIA)
            graph.account.loadInventory()
        }
        graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
    }

    /** The live fixture is endless and paced, so the clock is moved a little rather than run to idle. */
    private fun settle() {
        dispatcher.scheduler.advanceTimeBy(600)
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(start: Int = 1) = PhoneCameraViewModel(
        graph.account,
        graph.settingsStore,
        start,
        graph.playbackFactory,
        { today },
        dispatcher,
    ) { _ ->
        graph.playbackFactory.create(
            opener = { camera, quality, onStatus -> graph.account.openStream(camera, quality, onStatus) },
            capabilities = TestGraph.tvCapabilities,
            scope = this,
        )
    }

    @Test
    fun `starts live on the given camera and lists the day without playing footage`() = runTest(dispatcher) {
        val vm = viewModel(start = 1)
        vm.onStart()
        settle()
        assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.LIVE)
        assertThat(vm.camera!!.channelNo).isEqualTo(2)
        assertThat(account.opened.map { it.first.channelNo }).containsExactly(2)
        assertThat(account.searched.single().second).isEqualTo(today)
        assertThat(
            vm.recorded.value!!
                .state.value.segments,
        ).hasSize(2)
        assertThat(account.playbackOpened).isEmpty()
        assertThat(vm.activePlayer.value).isSameInstanceAs(
            vm.live.players.value
                .single(),
        )
        vm.onStop()
        vm.live.release()
    }

    @Test
    fun `scrubbing stops live, plays footage from that time, and Live comes back`() = runTest(dispatcher) {
        val vm = viewModel(start = 1)
        vm.onStart()
        settle()
        vm.scrubTo(0.5f) // noon: inside the first segment (06:00–12:30)
        settle()
        assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
        assertThat(vm.activePlayer.value).isSameInstanceAs(vm.recorded.value!!.player)
        assertThat(
            account.playbackOpened
                .single()
                .second.begin,
        ).isEqualTo(today.atTime(12, 0))
        assertThat(
            vm.recorded.value!!
                .state.value.phase,
        ).isEqualTo(PlaybackPhase.PLAYING)
        val liveOpens = account.opened.size
        vm.goLive()
        settle()
        assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.LIVE)
        assertThat(vm.activePlayer.value).isSameInstanceAs(
            vm.live.players.value
                .single(),
        )
        assertThat(account.opened.size).isEqualTo(liveOpens + 1)
        vm.onStop()
        vm.live.release()
    }

    @Test
    fun `a day change while live only relists the bar`() = runTest(dispatcher) {
        val vm = viewModel(start = 0)
        vm.onStart()
        settle()
        vm.changeDay(-1)
        settle()
        assertThat(account.searched.map { it.second }).containsExactly(today, today.minusDays(1)).inOrder()
        assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.LIVE)
        assertThat(account.playbackOpened).isEmpty()
        vm.changeDay(+2) // tomorrow is refused
        settle()
        assertThat(account.searched).hasSize(2)
        vm.cycleSpeed() // ignored while live
        assertThat(
            vm.recorded.value!!
                .state.value.speed.isRealTime,
        ).isTrue()
        vm.onStop()
        vm.live.release()
    }

    @Test
    fun `stepping wraps, returns to live and lists the new camera's day`() = runTest(dispatcher) {
        val vm = viewModel(start = 2)
        vm.onStart()
        settle()
        vm.scrubTo(0.5f)
        settle()
        val old = vm.recorded.value!!
        vm.step(+1)
        settle()
        assertThat(vm.index.value).isEqualTo(0)
        assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.LIVE)
        assertThat(vm.recorded.value).isNotSameInstanceAs(old)
        assertThat(
            account.searched
                .last()
                .first.channelNo,
        ).isEqualTo(1)
        assertThat(
            account.opened
                .last()
                .first.channelNo,
        ).isEqualTo(1)
        vm.step(-1)
        assertThat(vm.index.value).isEqualTo(2)
        vm.onStop()
        vm.live.release()
    }

    @Test
    fun `stop while playing footage releases it and start resumes it`() = runTest(dispatcher) {
        val vm = viewModel(start = 0)
        vm.onStart()
        settle()
        vm.scrubTo(0.3f)
        settle()
        val opens = account.playbackOpened.size
        vm.onStop()
        settle()
        vm.onStart()
        settle()
        assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
        assertThat(account.playbackOpened.size).isEqualTo(opens + 1)
        vm.onStop()
        vm.live.release()
    }
}
