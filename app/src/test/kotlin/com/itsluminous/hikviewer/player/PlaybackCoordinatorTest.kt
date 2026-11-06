// kotlinx-coroutines-test's dispatcher and time-control API is still marked experimental.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.itsluminous.hikviewer.player

import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.fakes.NoopKeyframePool
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.SyntheticStream
import com.itsluminous.hikviewer.fakes.TestGraph
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    /**
     * A probe the test sets by hand and that records its start/stop calls. Its readings default
     * to "everything is free": a coordinator may only believe them while it has started the probe.
     */
    private class FakeProbe : HeadroomProbe {
        var started = 0
        var stopped = 0
        override var cpuIdleFraction = 1f
        override var spareMemoryBytes = Long.MAX_VALUE

        override fun start() {
            started++
        }

        override fun stop() {
            stopped++
        }
    }

    private val probe = FakeProbe()

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        capabilities: DeviceCapabilities = TestGraph.tvCapabilities,
    ) = PlaybackCoordinator(
        opener = { camera, _, onStatus ->
            opened += camera.key
            SyntheticStream.flow(onStatus, paced = false).take(FRAMES)
        },
        capabilities = capabilities,
        scope = this,
        pool = pool,
        pickCodec = decoders::pickCodec,
        decoderFactory = decoders.factory,
        tickMillis = 500,
        clock = { now },
        dispatcher = dispatcher,
        releaser = {
            it.release()
            java.util.concurrent.CompletableFuture
                .completedFuture(null)
        },
        headroom = probe,
    )

    private val pool = NoopKeyframePool()

    @Test
    fun `the headroom probe runs only with tiles beyond the ceiling and its readings lift the budget above it`() =
        runTest(dispatcher) {
            val c = coordinator()
            c.setCameras(cameras(4)) // ceiling is 8 on the test TV: nothing to probe for
            c.start()
            assertThat(probe.started).isEqualTo(0)
            c.setCameras(cameras(10))
            assertThat(probe.started).isEqualTo(1)
            probe.cpuIdleFraction = 0f
            probe.spareMemoryBytes = 0L
            c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
            dispatcher.scheduler.runCurrent()
            assertThat(c.players.value.count { it.mode.isLive }).isEqualTo(8)
            // Busy: the budget stays at the ceiling however long it is calm.
            repeat(60) {
                now += 500
                dispatcher.scheduler.advanceTimeBy(500)
                dispatcher.scheduler.runCurrent()
            }
            assertThat(c.liveLimit).isEqualTo(8)
            // A free core and spare memory: the calm is already there, so one more tile at once (and
            // the next only after another calm period, outside this window).
            probe.cpuIdleFraction = 1f
            probe.spareMemoryBytes = 500L shl 20
            repeat(20) {
                now += 500
                dispatcher.scheduler.advanceTimeBy(500)
                dispatcher.scheduler.runCurrent()
            }
            assertThat(c.liveLimit).isEqualTo(9)
            assertThat(c.players.value.count { it.mode.isLive }).isEqualTo(9)
            c.stop()
            assertThat(probe.stopped).isAtLeast(1)
            c.release()
        }

    @Test
    fun `below the ceiling the probe is never read and a grid of four stays at the ceiling however idle it looks`() =
        runTest(dispatcher) {
            val c = coordinator()
            c.setCameras(cameras(4))
            c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
            c.start()
            dispatcher.scheduler.runCurrent()
            assertThat(probe.started).isEqualTo(0)
            repeat(120) {
                now += 500
                dispatcher.scheduler.advanceTimeBy(500)
                dispatcher.scheduler.runCurrent()
            }
            assertThat(c.liveLimit).isEqualTo(8)
            // Growing the grid past the ceiling starts the probe; the limit still is the ceiling, so
            // the new tiles beyond it start as stills, not as twelve software decoders at once.
            c.setCameras(cameras(12))
            c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
            dispatcher.scheduler.runCurrent()
            assertThat(probe.started).isEqualTo(1)
            assertThat(c.players.value.count { it.mode.isLive }).isEqualTo(8)
            c.release()
        }

    @Test
    fun `shrinking the grid back under the ceiling stops the probe and drops a probed limit back to it`() =
        runTest(dispatcher) {
            val c = coordinator()
            c.setCameras(cameras(10))
            c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
            c.start()
            probe.spareMemoryBytes = 500L shl 20
            repeat(40) {
                now += 500
                dispatcher.scheduler.advanceTimeBy(500)
                dispatcher.scheduler.runCurrent()
            }
            assertThat(c.liveLimit).isEqualTo(9)
            c.setCameras(cameras(4))
            assertThat(probe.stopped).isEqualTo(1)
            repeat(30) {
                now += 500
                dispatcher.scheduler.advanceTimeBy(500)
                dispatcher.scheduler.runCurrent()
            }
            assertThat(c.liveLimit).isEqualTo(8)
            c.release()
        }

    @Test
    fun `a user ceiling reassigns the tiles at once, stops the probe, and Automatic brings both back`() =
        runTest(dispatcher) {
            val c = coordinator()
            c.setCameras(cameras(16))
            c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
            c.start()
            dispatcher.scheduler.runCurrent()
            assertThat(c.automaticCeiling).isEqualTo(8)
            assertThat(c.players.value.count { it.mode.isLive }).isEqualTo(8)
            assertThat(probe.started).isEqualTo(1)
            c.setLiveCeiling(12)
            assertThat(c.liveLimit).isEqualTo(12)
            assertThat(c.players.value.map { it.mode.isLive }).isEqualTo(List(12) { true } + List(4) { false })
            assertThat(probe.stopped).isEqualTo(1)
            // The probe's rosy readings are not read while it is off: the limit stays at exactly 12.
            repeat(60) {
                now += 500
                dispatcher.scheduler.advanceTimeBy(500)
                dispatcher.scheduler.runCurrent()
            }
            assertThat(c.liveLimit).isEqualTo(12)
            // The same value again is a no-op (applySettings re-applies it on every grid change).
            c.setLiveCeiling(12)
            assertThat(probe.stopped).isEqualTo(1)
            // Down to 4: eight tiles release their decoders, four stay live.
            c.setLiveCeiling(4)
            assertThat(c.players.value.count { it.mode.isLive }).isEqualTo(4)
            // Automatic: the device ceiling, and the probe is worth running again for the 16 tiles.
            c.setLiveCeiling(null)
            assertThat(c.liveLimit).isEqualTo(8)
            assertThat(c.players.value.count { it.mode.isLive }).isEqualTo(8)
            assertThat(probe.started).isEqualTo(2)
            c.release()
        }

    @Test
    fun `a user ceiling chosen before start is budgeted from the first tile on`() = runTest(dispatcher) {
        val c = coordinator()
        c.setLiveCeiling(6)
        c.setCameras(cameras(12))
        assertThat(c.players.value.count { it.mode.isLive }).isEqualTo(6)
        c.start()
        dispatcher.scheduler.runCurrent()
        assertThat(probe.started).isEqualTo(0)
        c.release()
    }

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
        // The shared keyframe decoders go too: an idle one still holds ~17 MB in media.codec.
        assertThat(pool.codecsClosed).isEqualTo(1)
        c.release()
    }

    @Test
    fun `a stall with a deep queue counts as lag and demotes a tile within the demote window`() = runTest(dispatcher) {
        val c = coordinator()
        c.setCameras(cameras(4))
        c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
        c.heroIndex = 0
        c.start()
        dispatcher.scheduler.runCurrent()
        val before = c.liveLimit
        // One stall event with pictures piled up at that decoder's input.
        decoders.created.last().stalled = true
        decoders.created.last().queued = LiveBudget.LAG_FRAMES + 1
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
    fun `a decoder that stalls with an empty queue is recreated but never costs a tile`() = runTest(dispatcher) {
        val c = coordinator()
        c.setCameras(cameras(4))
        c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
        c.heroIndex = 0
        c.start()
        dispatcher.scheduler.runCurrent()
        val before = c.liveLimit
        val wedged = decoders.created.last()
        wedged.stalled = true // queue stays 0: the codec wedged on its own
        repeat(6) {
            now += 500
            dispatcher.scheduler.advanceTimeBy(500)
            dispatcher.scheduler.runCurrent()
            wedged.stalled = false
        }
        assertThat(wedged.released).isTrue()
        assertThat(c.liveLimit).isEqualTo(before)
        c.release()
    }

    @Test
    fun `the hero drives the hardware decoder and is the one unpaced tile`() {
        runTest(dispatcher) {
            val c = coordinator()
            c.setCameras(cameras(4))
            assertThat(c.players.value.all { it.mode == TileMode.SW_LIVE }).isTrue()
            assertThat(c.players.value.all { it.paced }).isTrue() // a flat grid: even motion everywhere
            c.heroIndex = 2
            assertThat(c.players.value.map { it.mode })
                .containsExactly(
                    TileMode.SW_LIVE,
                    TileMode.SW_LIVE,
                    TileMode.HW_LIVE,
                    TileMode.SW_LIVE,
                ).inOrder()
            assertThat(c.players.value.map { it.paced }).containsExactly(true, true, false, true).inOrder()
            c.heroIndex = 0
            assertThat(c.players.value.map { it.paced }).containsExactly(false, true, true, true).inOrder()
            c.release()
        }
    }

    @Test
    fun `every tile is handed the presentation divisor the policy gives it`() = runTest(dispatcher) {
        val c = coordinator()
        c.setCameras(cameras(16))
        c.heroIndex = 0
        val players = c.players.value
        val divisors = players.map { it.presentEvery }
        assertThat(divisors).isEqualTo(players.indices.map { PresentationRate.divisor(16, isHero = it == 0) })
        // The switch is off tonight: every tile presents every picture.
        assertThat(divisors.toSet()).containsExactly(PresentationRate.FULL_RATE)
        c.release()
    }

    @Test
    fun `hardware only keeps the hero live, releases the rest and stop lifts the cap`() = runTest(dispatcher) {
        val c = coordinator()
        c.setCameras(cameras(4))
        c.players.value.forEach { it.attachSurface(Shadow.newInstanceOf(Surface::class.java)) }
        c.heroIndex = 0
        c.start()
        dispatcher.scheduler.runCurrent()
        val softwareDecoders = decoders.created.filter { it.codecName.startsWith("fake.sw.") }
        assertThat(softwareDecoders).hasSize(3)
        c.hardwareOnly = true
        assertThat(c.players.value.map { it.mode })
            .containsExactly(TileMode.HW_LIVE, TileMode.KEYFRAME, TileMode.KEYFRAME, TileMode.KEYFRAME)
            .inOrder()
        // The software decoders are gone at once (they must free the CPU), the hero's is untouched.
        assertThat(softwareDecoders.all { it.released }).isTrue()
        assertThat(decoders.created.single { it.codecName.startsWith("fake.hw.") }.released).isFalse()
        c.hardwareOnly = false
        assertThat(c.players.value.count { it.mode.isLive }).isEqualTo(4)
        c.hardwareOnly = true
        c.stop()
        assertThat(c.hardwareOnly).isFalse()
        c.release()
    }

    @Test
    fun `low-ram device limits live tiles and the ticker demotes on lag`() = runTest(dispatcher) {
        val lowRam = DeviceCapabilities(isLowRamDevice = true, totalMemoryBytes = 1_024L shl 20, cpuCores = 4)
        val c = coordinator(lowRam)
        c.setCameras(cameras(8))
        c.heroIndex = 0
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
