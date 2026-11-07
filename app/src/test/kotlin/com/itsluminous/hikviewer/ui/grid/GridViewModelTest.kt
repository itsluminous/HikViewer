// kotlinx-coroutines-test's dispatcher and time-control API is still marked experimental.
@file:OptIn(ExperimentalCoroutinesApi::class)

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        return GridViewModel(graph.account, graph.settingsStore, graph.arrangementStore) { _ ->
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
        val serial = FakeCloudServices.SERIAL
        // Camera 3 was moved to the front of the 1 + 7 layout (position 0 = hero).
        graph.arrangementStore.setOrder(GridLayoutMode.HERO_1_7, listOf("$serial/3", "$serial/1", "$serial/2"))
        val vm = viewModel()
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        vm.focusedIndex = 5
        assertThat(vm.playback.heroIndex).isNull()
        // Focus is a view matter: every tile keeps the mode the budget gave it.
        assertThat(
            vm.playback.players.value
                .none { it.mode == TileMode.HW_LIVE },
        ).isTrue()
        assertThat(ready(vm).cameras.first().channelNo).isEqualTo(1) // the 4x3 arrangement is untouched
        vm.setLayout(GridLayoutMode.HERO_1_7)
        assertThat(graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.HERO_1_7)
        assertThat(ready(vm).layout).isEqualTo(GridLayoutMode.HERO_1_7)
        assertThat(ready(vm).cameras.map { it.channelNo }.take(4)).containsExactly(3, 1, 2, 4).inOrder()
        assertThat(vm.playback.heroIndex).isEqualTo(0)
        assertThat(
            vm.playback.players.value[0]
                .camera.channelNo,
        ).isEqualTo(3)
        assertThat(
            vm.playback.players.value[0]
                .mode,
        ).isEqualTo(TileMode.HW_LIVE)
        assertThat(
            vm.playback.players.value[5]
                .mode,
        ).isEqualTo(TileMode.SW_LIVE)
        // 12 cameras have a signal but a 1 + 7 layout shows 8 tiles: only those 8 get a stream.
        assertThat(ready(vm).cameras).hasSize(12)
        assertThat(vm.playback.players.value).hasSize(8)
        val streamed =
            vm.playback.players.value
                .map { it.camera.channelNo }
        assertThat(streamed).containsExactly(3, 1, 2, 4, 5, 6, 7, 8).inOrder()
        vm.setLayout(GridLayoutMode.AUTO) // 12 cameras resolve to 4 x 3: every camera gets a tile again
        assertThat(vm.playback.players.value).hasSize(12)
        vm.playback.release()
    }

    @Test
    fun `an even 1+N layout shows every tile upright and evicts the last one turned, keeping the arrangement`() =
        runTest(dispatcher) {
            graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
            val serial = FakeCloudServices.SERIAL
            graph.arrangementStore.lastLayout = GridLayoutMode.HERO_1_6
            graph.arrangementStore.setOrder(GridLayoutMode.HERO_1_6, listOf("$serial/3", "$serial/1", "$serial/2"))
            val vm = viewModel()
            vm.portrait = true
            vm.load()
            dispatcher.scheduler.advanceUntilIdle()
            // Upright: the hero and three rows of two, seven streams.
            assertThat(streamed(vm)).containsExactly(3, 1, 2, 4, 5, 6, 7).inOrder()
            assertThat(vm.playback.heroIndex).isEqualTo(0)
            // Turned: the square 1 + 5 grid has no cell for the seventh tile; its stream closes.
            vm.portrait = false
            vm.applySettings()
            assertThat(streamed(vm)).containsExactly(3, 1, 2, 4, 5, 6).inOrder()
            // A move while turned edits the same list: position 6 (channel 7) is still there, unseen.
            vm.moveTile(0, 1)
            assertThat(graph.arrangementStore.order(GridLayoutMode.HERO_1_6)!!.take(7))
                .containsExactlyElementsIn(listOf(1, 3, 2, 4, 5, 6, 7).map { "$serial/$it" })
                .inOrder()
            // Upright again: the seventh tile is back where it was.
            vm.portrait = true
            vm.applySettings()
            assertThat(streamed(vm)).containsExactly(1, 3, 2, 4, 5, 6, 7).inOrder()
            vm.playback.release()
        }

    /** Channels of the tiles that have a stream, in grid order. */
    private fun streamed(vm: GridViewModel): List<Int> = vm.playback.players.value
        .map { it.camera.channelNo }

    @Test
    fun `moving a tile swaps, persists per layout, follows focus and keeps the players`() = runTest(dispatcher) {
        graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        val vm = viewModel()
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onStart()
        settle()
        val before = vm.playback.players.value
        val origin = vm.moveTile(0, 5)
        assertThat(origin!!.map { it.substringAfter('/') }.take(2)).containsExactly("1", "2").inOrder()
        assertThat(ready(vm).cameras.map { it.channelNo }.take(6)).containsExactly(6, 2, 3, 4, 5, 1).inOrder()
        assertThat(vm.focusedIndex).isEqualTo(5)
        assertThat(graph.arrangementStore.order(GridLayoutMode.AUTO)!!.first()).endsWith("/6")
        assertThat(graph.arrangementStore.order(GridLayoutMode.HERO_1_7)).isNull()
        // Same TilePlayer objects, reordered: no stream was reopened.
        assertThat(vm.playback.players.value).containsExactlyElementsIn(before)
        assertThat(cloud.accounts.single().opened).hasSize(12)
        // Another layout has its own arrangement; coming back restores this one.
        vm.setLayout(GridLayoutMode.GRID_4X4)
        assertThat(ready(vm).cameras.first().channelNo).isEqualTo(1)
        vm.setLayout(GridLayoutMode.AUTO)
        assertThat(ready(vm).cameras.first().channelNo).isEqualTo(6)
        // Back during a move puts the origin back.
        vm.restoreArrangement(origin)
        assertThat(ready(vm).cameras.map { it.channelNo }.take(6)).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
        // Out-of-range or same-slot moves change nothing.
        assertThat(vm.moveTile(3, 3)).isEqualTo(ready(vm).cameras.map { it.key })
        assertThat(vm.moveTile(0, 99)).isEqualTo(ready(vm).cameras.map { it.key })
        vm.onStop()
        vm.playback.release()
    }

    @Test
    fun `a move caps live decoding to the hero and drop, cancel or stop restore the budget`() = runTest(dispatcher) {
        graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        graph.arrangementStore.lastLayout = GridLayoutMode.HERO_1_7
        val vm = viewModel()
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onStart()
        settle()
        val live = {
            vm.playback.players.value
                .count { it.mode.isLive }
        }
        assertThat(live()).isEqualTo(8)
        vm.onMoveStarted()
        assertThat(live()).isEqualTo(1)
        assertThat(
            vm.playback.players.value[0]
                .mode,
        ).isEqualTo(TileMode.HW_LIVE)
        // Swaps during the move keep the cap.
        vm.moveTile(3, 4)
        assertThat(live()).isEqualTo(1)
        vm.onMoveEnded()
        assertThat(live()).isEqualTo(8)
        // Leaving the screen mid-move must not leave the cap behind for the next start.
        vm.onMoveStarted()
        vm.onStop()
        assertThat(vm.playback.hardwareOnly).isFalse()
        vm.onStart()
        settle()
        assertThat(live()).isEqualTo(8)
        vm.onStop()
        vm.playback.release()
    }

    @Test
    fun `changing a tile's camera swaps or replaces, persists per layout and picks the hero`() = runTest(dispatcher) {
        graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        val vm = viewModel()
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        vm.onStart()
        settle()
        val key = { n: Int -> "${FakeCloudServices.SERIAL}/$n" }
        val before = vm.playback.players.value

        // 12 cameras, AUTO → 4x3: every camera is on the grid, so a pick trades places.
        vm.changeTile(2, key(7))
        assertThat(ready(vm).cameras.map { it.channelNo }.take(8)).containsExactly(1, 2, 7, 4, 5, 6, 3, 8).inOrder()
        assertThat(vm.focusedIndex).isEqualTo(2)
        assertThat(graph.arrangementStore.order(GridLayoutMode.AUTO)!![2]).isEqualTo(key(7))
        assertThat(vm.playback.players.value).containsExactlyElementsIn(before) // nothing reconnected
        // Picking the camera already there changes nothing.
        vm.changeTile(2, key(7))
        assertThat(ready(vm).cameras[2].channelNo).isEqualTo(7)

        // 1 + 5 shows six tiles: a camera beyond them replaces this tile's camera, which leaves the screen.
        vm.setLayout(GridLayoutMode.HERO_1_5)
        assertThat(ready(vm).cameras.map { it.channelNo }.take(6)).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
        vm.changeTile(3, key(11))
        settle()
        val shown =
            vm.playback.players.value
                .map { it.camera.channelNo }
        assertThat(shown).containsExactly(1, 2, 3, 11, 5, 6).inOrder()
        assertThat(graph.arrangementStore.order(GridLayoutMode.HERO_1_5)!!.indexOf(key(4))).isEqualTo(10)
        // Position 0 is the hero: Change there picks the hero camera.
        vm.changeTile(0, key(9))
        settle()
        assertThat(
            vm.playback.players.value
                .first()
                .camera.channelNo,
        ).isEqualTo(9)
        assertThat(vm.playback.heroIndex).isEqualTo(0)
        // The 4x3 arrangement was not touched.
        vm.setLayout(GridLayoutMode.AUTO)
        assertThat(ready(vm).cameras.map { it.channelNo }.take(3)).containsExactly(1, 2, 7).inOrder()
        vm.onStop()
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

    /**
     * The grid always runs the automatic ceiling: the Live cameras setting of 0.1 is gone, and a
     * count it stored (here 12 under the old key) must not slow the TV down after the update.
     */
    @Test
    fun `a stored live-camera count from an older build is ignored and the device ceiling applies`() =
        runTest(dispatcher) {
            ApplicationProvider
                .getApplicationContext<android.content.Context>()
                .getSharedPreferences("test-settings", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("liveCameras", 12)
                .commit()
            graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
            graph.settingsStore.visibleCameraKeys = (1..16).map { "${FakeCloudServices.SERIAL}/$it" }
            graph.arrangementStore.lastLayout = GridLayoutMode.GRID_4X4
            val vm = viewModel()
            vm.load()
            dispatcher.scheduler.advanceUntilIdle()
            val players = vm.playback.players.value
            assertThat(players).hasSize(16)
            // Automatic on the 2 GB test TV: 8 live, 8 stills, whatever the preference says.
            assertThat(vm.playback.liveCeiling).isEqualTo(8)
            assertThat(vm.playback.liveLimit).isEqualTo(8)
            assertThat(players.count { it.mode.isLive }).isEqualTo(8)
            assertThat(players.drop(8).all { it.mode == TileMode.KEYFRAME }).isTrue()
            vm.applySettings()
            assertThat(vm.playback.liveCeiling).isEqualTo(8)
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
            GridViewModel(graph.account, graph.settingsStore, graph.arrangementStore) { _ ->
                graph.playbackFactory.create({ c, q, s -> graph.account.openStream(c, q, s) }, graph.capabilities, this)
            }
        vm.load()
        settle()
        assertThat(vm.state.value).isInstanceOf(GridUiState.Error::class.java)
        vm.playback.release()
    }
}
