package com.itsluminous.hikviewer.ui.grid

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.TileMode
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

@RunWith(AndroidJUnit4::class)
class GridViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val cloud = FakeCloudServices(cameraCount = 16, signalCount = 12)
    private val decoders = RecordingDecoderFactory()
    private lateinit var graph: AppGraph

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
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): GridViewModel {
        kotlinx.coroutines.runBlocking { graph.account.login("a", "secret", Region.INDIA) }
        return GridViewModel(graph.account, graph.settingsStore) { _ ->
            graph.playbackFactory.create(
                opener = { c, q, s -> graph.account.openStream(c, q, s) },
                capabilities = graph.capabilities,
                scope = this,
            )
        }
    }

    private fun ready(vm: GridViewModel) = vm.state.value as GridUiState.Ready

    /** Runs pending work while playback is on; `advanceUntilIdle` would spin the watchdog ticker forever. */
    private fun settle() {
        dispatcher.scheduler.advanceTimeBy(100)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `loads cameras with signal and asks for the code before streaming`() = runTest(dispatcher) {
        val vm = viewModel()
        assertThat(vm.state.value).isEqualTo(GridUiState.Loading)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        val state = ready(vm)
        assertThat(state.cameras).hasSize(12)
        assertThat(state.layout).isEqualTo(GridLayoutMode.AUTO)
        assertThat(state.heroIndex).isEqualTo(0)
        assertThat(state.needsCode).isTrue()
        vm.onStart()
        assertThat(cloud.accounts.single().opened).isEmpty()
        vm.playback.release()
    }

    @Test
    fun `wrong code is flagged, right code is stored and starts playback`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onStart()
        assertThat(vm.submitVerificationCode("nope")).isFalse()
        assertThat(ready(vm).codeRejected).isTrue()
        assertThat(vm.submitVerificationCode(" ${FakeCloudServices.GOOD_CODE} ")).isTrue()
        assertThat(ready(vm).needsCode).isFalse()
        assertThat(graph.settingsStore.verificationCode).isEqualTo(FakeCloudServices.GOOD_CODE)
        dispatcher.scheduler.runCurrent()
        assertThat(cloud.accounts.single().opened).hasSize(12)
        vm.onStop()
        vm.playback.release()
    }

    @Test
    fun `layout change persists and hero drives the hardware decoder`() = runTest(dispatcher) {
        graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        graph.settingsStore.heroCameraKey = "${FakeCloudServices.SERIAL}/3"
        val vm = viewModel()
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        vm.focusedIndex = 5
        assertThat(vm.playback.heroIndex).isNull()
        vm.setLayout(GridLayoutMode.HERO)
        assertThat(graph.settingsStore.gridLayout).isEqualTo(GridLayoutMode.HERO)
        assertThat(ready(vm).layout).isEqualTo(GridLayoutMode.HERO)
        assertThat(vm.playback.heroIndex).isEqualTo(2)
        assertThat(
            vm.playback.players.value[2]
                .mode,
        ).isEqualTo(TileMode.HW_LIVE)
        assertThat(
            vm.playback.players.value[5]
                .mode,
        ).isEqualTo(TileMode.HW_LIVE)
        vm.playback.release()
    }

    @Test
    fun `settings choose the visible cameras`() = runTest(dispatcher) {
        graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        graph.settingsStore.visibleCameraKeys =
            listOf("${FakeCloudServices.SERIAL}/14", "${FakeCloudServices.SERIAL}/1")
        val vm = viewModel()
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(ready(vm).cameras.map { it.channelNo }).containsExactly(14, 1).inOrder()
        assertThat(
            vm.playback.players.value
                .map { it.camera.channelNo },
        ).containsExactly(14, 1).inOrder()
        vm.playback.release()
    }

    @Test
    fun `playback follows onStart and onStop`() = runTest(dispatcher) {
        graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        val vm = viewModel()
        vm.onStart()
        vm.load()
        dispatcher.scheduler.advanceTimeBy(100)
        dispatcher.scheduler.runCurrent()
        assertThat(cloud.accounts.single().opened).hasSize(12)
        vm.onStop()
        vm.onStart()
        dispatcher.scheduler.runCurrent()
        assertThat(cloud.accounts.single().opened).hasSize(24)
        vm.playback.release()
    }

    @Test
    fun `a stream rejecting a code that still matches the recorder keeps the code and does not prompt`() =
        runTest(dispatcher) {
            graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
            val vm = viewModel()
            vm.onStart()
            vm.load()
            settle()
            val devicesBefore = cloud.deviceListings
            vm.onStreamRejectedCode()
            vm.onStreamRejectedCode() // a second tile reporting the same thing shares the re-check
            settle()
            assertThat(cloud.deviceListings).isEqualTo(devicesBefore + 1)
            assertThat(graph.settingsStore.verificationCode).isEqualTo(FakeCloudServices.GOOD_CODE)
            assertThat(ready(vm).needsCode).isFalse()
            // Streams were not torn down: still the first 12 opens.
            assertThat(cloud.accounts.single().opened).hasSize(12)
            vm.playback.release()
        }

    @Test
    fun `a stream rejecting the code after the recorder's hash changed re-prompts`() = runTest(dispatcher) {
        graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        val vm = viewModel()
        vm.onStart()
        vm.load()
        settle()
        cloud.encryptPwd = "0123456789abcdef0123456789abcdef"
        vm.onStreamRejectedCode()
        settle()
        assertThat(graph.settingsStore.verificationCode).isNull()
        assertThat(ready(vm).needsCode).isTrue()
        vm.playback.release()
    }

    @Test
    fun `a stored code that no longer matches the recorder is asked for again at launch`() = runTest(dispatcher) {
        graph.settingsStore.verificationCode = "STALE1"
        val vm = viewModel()
        vm.onStart()
        vm.load()
        settle()
        assertThat(ready(vm).needsCode).isTrue()
        assertThat(cloud.accounts.single().opened).isEmpty()
        vm.playback.release()
    }

    @Test
    fun `retry reopens only that tile's stream`() = runTest(dispatcher) {
        graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        val vm = viewModel()
        vm.onStart()
        vm.load()
        settle()
        assertThat(cloud.accounts.single().opened).hasSize(12)
        vm.retry(3)
        dispatcher.scheduler.runCurrent()
        val opened = cloud.accounts.single().opened
        assertThat(opened).hasSize(13)
        assertThat(opened.last().first.channelNo).isEqualTo(4)
        vm.playback.release()
    }

    @Test
    fun `cloud failure shows an error state`() = runTest(dispatcher) {
        val vm =
            GridViewModel(graph.account, graph.settingsStore) { _ ->
                graph.playbackFactory.create({ c, q, s -> graph.account.openStream(c, q, s) }, graph.capabilities, this)
            }
        vm.load()
        settle()
        assertThat(vm.state.value).isInstanceOf(GridUiState.Error::class.java)
        vm.playback.release()
    }
}
