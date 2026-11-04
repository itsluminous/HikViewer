package com.itsluminous.hikviewer.player

import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.fakes.NoopKeyframePool
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.SyntheticStream
import com.itsluminous.hikviewer.fakes.TestGraph
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow

@RunWith(AndroidJUnit4::class)
class PlaybackCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val decoders = RecordingDecoderFactory()
    private val opened = mutableListOf<String>()
    private var now = 0L

    private fun cameras(n: Int) = (1..n).map { Camera("S", it, "c$it", hasSignal = true, isOnline = true) }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        capabilities: DeviceCapabilities = TestGraph.tvCapabilities,
    ) = PlaybackCoordinator(
        opener = { camera, _, onStatus ->
            opened += camera.key
            SyntheticStream.flow(onStatus, paced = false).take(FRAMES)
        },
        capabilities = capabilities,
        scope = this,
        pool = NoopKeyframePool(),
        pickCodec = decoders::pickCodec,
        decoderFactory = decoders.factory,
        tickMillis = 500,
        clock = { now },
        dispatcher = dispatcher,
    )

    @Test
    fun `start opens every stream in parallel and stop releases everything`() = runTest(dispatcher) {
        val c = coordinator()
        c.setCameras(cameras(4))
        c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
        c.start()
        dispatcher.scheduler.runCurrent()
        assertThat(opened).hasSize(4)
        assertThat(decoders.created.count { it.started }).isEqualTo(4)
        c.stop()
        assertThat(decoders.created.all { it.released }).isTrue()
        c.release()
    }

    @Test
    fun `a stalled decoder counts as lag and demotes a tile within the demote window`() = runTest(dispatcher) {
        val c = coordinator()
        c.setCameras(cameras(4))
        c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
        c.focusedIndex = 0
        c.start()
        dispatcher.scheduler.runCurrent()
        val before = c.liveLimit
        // One stall event; the queues stay shallow.
        decoders.created.last().stalled = true
        now = 500
        dispatcher.scheduler.advanceTimeBy(500)
        dispatcher.scheduler.runCurrent()
        decoders.created.forEach { it.stalled = false }
        assertThat(c.liveLimit).isEqualTo(before)
        // The stall keeps counting as lag for LAG_DEMOTE_MILLIS, so the budget sheds one tile.
        repeat(5) {
            now += 500
            dispatcher.scheduler.advanceTimeBy(500)
            dispatcher.scheduler.runCurrent()
        }
        assertThat(c.liveLimit).isEqualTo(before - 1)
        c.release()
    }

    @Test
    fun `focus and hero drive the modes, released before created`() = runTest(dispatcher) {
        val c = coordinator()
        c.setCameras(cameras(4))
        c.focusedIndex = 0
        assertThat(c.players.value.map { it.mode })
            .containsExactly(
                TileMode.HW_LIVE,
                TileMode.SW_LIVE,
                TileMode.SW_LIVE,
                TileMode.SW_LIVE,
            ).inOrder()
        c.heroIndex = 2
        assertThat(c.players.value[2].mode).isEqualTo(TileMode.HW_LIVE)
        c.focusedIndex = 3
        assertThat(c.players.value.map { it.mode })
            .containsExactly(
                TileMode.SW_LIVE,
                TileMode.SW_LIVE,
                TileMode.HW_LIVE,
                TileMode.HW_LIVE,
            ).inOrder()
        c.release()
    }

    @Test
    fun `low-ram device limits live tiles and the ticker demotes on lag`() = runTest(dispatcher) {
        val lowRam = DeviceCapabilities(isLowRamDevice = true, totalMemoryBytes = 1_024L shl 20, cpuCores = 4)
        val c = coordinator(lowRam)
        c.setCameras(cameras(8))
        c.focusedIndex = 0
        assertThat(c.liveLimit).isEqualTo(4)
        assertThat(c.players.value.count { it.mode.isLive }).isEqualTo(4)
        c.start()
        c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
        dispatcher.scheduler.advanceTimeBy(100)
        dispatcher.scheduler.runCurrent()
        decoders.created.forEach { it.queued = 20 }
        repeat(6) {
            now += 500
            dispatcher.scheduler.advanceTimeBy(500)
            dispatcher.scheduler.runCurrent()
        }
        assertThat(c.liveLimit).isEqualTo(3)
        assertThat(c.players.value.count { it.mode.isLive }).isEqualTo(3)
        c.release()
    }

    @Test
    fun `setCameras keeps players of cameras that stay and stops the others`() = runTest(dispatcher) {
        val c = coordinator()
        c.setCameras(cameras(3))
        c.start()
        dispatcher.scheduler.runCurrent()
        val kept = c.players.value[1]
        c.setCameras(cameras(3).drop(1))
        dispatcher.scheduler.runCurrent()
        assertThat(c.players.value.first()).isSameInstanceAs(kept)
        assertThat(opened).containsExactly("S/1", "S/2", "S/3")
        c.release()
    }

    @Test
    fun `start and stop are idempotent`() = runTest(dispatcher) {
        val c = coordinator()
        c.setCameras(cameras(1))
        c.start()
        c.start()
        dispatcher.scheduler.runCurrent()
        assertThat(opened).hasSize(1)
        c.stop()
        c.stop()
        c.release()
    }

    private companion object {
        const val FRAMES = 10
    }
}
