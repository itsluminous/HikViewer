// kotlinx-coroutines-test's dispatcher and time-control API is still marked experimental.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.itsluminous.hikviewer.player

import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.fakes.NoopKeyframePool
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.SyntheticStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow

/**
 * [TilePlayer] when a decoder fails: hardware is a preference and falls back to software whatever
 * the failure (round 18, for an Amlogic TV that failed the grid's hardware decoders with codes that
 * were neither 1100 nor transient), a software failure is the tile's error with the codec's own
 * words attached, and the hardware backoff, the configure retries and the user's Retry behave.
 */
@RunWith(AndroidJUnit4::class)
class TilePlayerFallbackTest {
    private val camera = Camera("S", 1, "cam", hasSignal = true, isOnline = true)
    private val decoders = RecordingDecoderFactory()
    private val pool = NoopKeyframePool()
    private val surface: Surface = Shadow.newInstanceOf(Surface::class.java)
    private val dispatcher = StandardTestDispatcher()
    private var now = 0L

    /** A sink with a size, so keyframe mode has somewhere to send thumbnails. */
    private val sink =
        object : StillSink {
            override val stillWidth = 320
            override val stillHeight = 180

            override fun onStill(
                pixels: IntArray,
                width: Int,
                height: Int,
            ) = Unit
        }

    /** Synchronous teardown, so a test can assert `released` right after the call that caused it. */
    private val releaseNow: (TileDecoder) -> java.util.concurrent.Future<*> = {
        it.release()
        java.util.concurrent.CompletableFuture
            .completedFuture(null)
    }

    private fun player(
        stream: Flow<AccessUnit> = SyntheticStream.flow(paced = false).take(FRAMES),
        statusHook: ((StreamStatus) -> Unit) -> Unit = {
            it(StreamStatus.Connecting(1))
            it(StreamStatus.Streaming)
        },
        releaser: (TileDecoder) -> java.util.concurrent.Future<*> = releaseNow,
        pool: KeyframeDecoderPool = this.pool,
    ) = TilePlayer(
        camera,
        opener = { _, _, onStatus ->
            statusHook(onStatus)
            stream
        },
        pool = pool,
        quality = StreamQuality.SUB,
        pickCodec = decoders::pickCodec,
        decoderFactory = decoders.factory,
        dispatcher = dispatcher,
        clock = { now },
        releaser = releaser,
    ).also { it.attachStillSink(sink) }

    private fun TestScope.run(player: TilePlayer) {
        player.start(this)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `hardware mode asks for a hardware codec and falls back to software on error 1100`() = runTest(dispatcher) {
        decoders.startFailures += "fake.hw.video/avc" to codecException(1100, transient = false)
        val p = player()
        p.attachSurface(surface)
        p.paced = false // a single-camera screen
        p.mode = TileMode.HW_LIVE
        run(p)
        assertThat(
            decoders.created.map { it.codecName },
        ).containsExactly("fake.hw.video/avc", "fake.sw.video/avc").inOrder()
        assertThat(decoders.created[0].released).isTrue()
        assertThat(decoders.created[0].renderSlotNanos).isEqualTo(0L)
        assertThat(decoders.created[1].started).isTrue()
        // The fallback keeps the screen's role: no jitter buffer, no 15 Hz slot, on software too.
        assertThat(decoders.created[1].renderSlotNanos).isEqualTo(0L)
        assertThat(decoders.created[1].pacedRequested).isFalse()
        p.stop()
    }

    /** Round 18: an Amlogic box failed the grid's hardware decoders with codes that are neither 1100 nor transient. */
    @Test
    fun `any hardware start failure falls back to software and the tile goes live`() = runTest(dispatcher) {
        decoders.startFailures +=
            "fake.hw.video/avc" to
            codecException(7, transient = false, diagnosticInfo = "android.media.MediaCodec.error_neg_38")
        val p = player()
        p.attachSurface(surface)
        p.mode = TileMode.HW_LIVE
        run(p)
        assertThat(decoders.created.map { it.codecName })
            .containsExactly("fake.hw.video/avc", "fake.sw.video/avc")
            .inOrder()
        assertThat(decoders.created[0].released).isTrue()
        val sw = decoders.created[1]
        assertThat(sw.started).isTrue()
        assertThat(p.state.value).isEqualTo(TileState.Connecting)
        sw.emitFirstFrame()
        assertThat(p.state.value).isEqualTo(TileState.Live)
        // The codec's words are kept for the log and the detail dialog even though the tile recovered.
        assertThat(p.lastDecoderError)
            .isEqualTo(
                "codec=fake.hw.video/avc error=7 transient=false recoverable=false " +
                    "diag=android.media.MediaCodec.error_neg_38",
            )
        assertThat(p.diagnostics(0)).contains("lastError[codec=fake.hw.video/avc error=7")
        p.stop()
    }

    @Test
    fun `an IO or illegal state failure of a hardware decoder falls back to software too`() = runTest(dispatcher) {
        for (failure in listOf(java.io.IOException("no such codec"), IllegalStateException("bad state"))) {
            decoders.created.clear()
            decoders.startFailures += "fake.hw.video/avc" to failure
            val p = player()
            p.attachSurface(surface)
            p.mode = TileMode.HW_LIVE
            run(p)
            assertThat(decoders.created.map { it.codecName })
                .containsExactly("fake.hw.video/avc", "fake.sw.video/avc")
                .inOrder()
            decoders.created[1].emitFirstFrame()
            assertThat(p.state.value).isEqualTo(TileState.Live)
            assertThat(
                p.lastDecoderError,
            ).isEqualTo("codec=fake.hw.video/avc error=${failure.javaClass.simpleName}: ${failure.message}")
            p.stop()
        }
    }

    @Test
    fun `a hardware decoder that fails at runtime with any code is replaced by software at the next keyframe`() =
        runTest(dispatcher) {
            val p = player(stream = SyntheticStream.flow(paced = true).take(FRAMES * 2))
            p.attachSurface(surface)
            p.mode = TileMode.HW_LIVE
            p.start(this)
            dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS * 3)
            dispatcher.scheduler.runCurrent()
            val hw = decoders.created.single()
            hw.emitFirstFrame()
            hw.listener.onError(hw, codecException(-2147483648, transient = false, diagnosticInfo = "vendor"))
            assertThat(hw.released).isTrue()
            assertThat(p.state.value).isNotInstanceOf(TileState.Error::class.java)
            dispatcher.scheduler.advanceUntilIdle()
            val sw = decoders.created.last()
            assertThat(sw.codecName).isEqualTo("fake.sw.video/avc")
            assertThat(sw.started).isTrue()
            assertThat(sw.fed.first().isKeyframe).isTrue()
            sw.emitFirstFrame()
            assertThat(p.state.value).isEqualTo(TileState.Live)
            assertThat(decoders.created).hasSize(2) // hardware is not tried again before the cooldown
            assertThat(p.lastDecoderError).contains("error=-2147483648 transient=false recoverable=false diag=vendor")
            p.stop()
        }

    @Test
    fun `a software failure after the hardware fallback is a tile error that carries the codec detail`() =
        runTest(dispatcher) {
            decoders.startFailures += "fake.hw.video/avc" to codecException(7, transient = false)
            decoders.startFailures += "fake.sw.video/avc" to codecException(9, transient = false, diagnosticInfo = "sw")
            val p = player()
            p.attachSurface(surface)
            p.mode = TileMode.HW_LIVE
            run(p)
            assertThat(decoders.created.map { it.codecName })
                .containsExactly("fake.hw.video/avc", "fake.sw.video/avc")
                .inOrder()
            assertThat(decoders.created.all { it.released }).isTrue()
            val error = p.state.value as TileState.Error
            assertThat(error.message).isEqualTo(TilePlayer.DECODER_ERROR)
            assertThat(
                error.detail,
            ).isEqualTo("codec=fake.sw.video/avc error=9 transient=false recoverable=false diag=sw")
            assertThat(p.lastDecoderError).isEqualTo(error.detail)
            p.stop()
        }

    @Test
    fun `a software failure on a tile that never tried hardware gets one hardware attempt`() = runTest(dispatcher) {
        // A grid tile beyond the hero asks for software first; the box's software decoder is
        // out of instances, its hardware one is free.
        decoders.startFailures += "fake.sw.video/avc" to codecException(0x80001000.toInt(), false)
        val p = player()
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        assertThat(decoders.created.map { it.codecName })
            .containsExactly("fake.sw.video/avc", "fake.hw.video/avc")
            .inOrder()
        assertThat(decoders.created[0].released).isTrue()
        assertThat(decoders.created[1].started).isTrue()
        decoders.created[1].emitFirstFrame()
        assertThat(p.state.value).isEqualTo(TileState.Live)
        p.stop()
    }

    @Test
    fun `when software and then hardware both fail the tile is an error, not a loop`() = runTest(dispatcher) {
        decoders.startFailures += "fake.sw.video/avc" to codecException(9, false, diagnosticInfo = "sw")
        decoders.startFailures += "fake.hw.video/avc" to codecException(7, false, diagnosticInfo = "hw")
        decoders.startFailures += "fake.sw.video/avc" to codecException(9, false, diagnosticInfo = "sw2")
        val p = player()
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        // software, hardware (second chance), software again (hardware's own fallback), then stop.
        assertThat(decoders.created.map { it.codecName })
            .containsExactly("fake.sw.video/avc", "fake.hw.video/avc", "fake.sw.video/avc")
            .inOrder()
        val error = p.state.value as TileState.Error
        assertThat(error.message).isEqualTo(TilePlayer.DECODER_ERROR)
        assertThat(error.detail).contains("diag=sw2")
        p.stop()
    }

    @Test
    fun `hardware refusals back off exponentially and a user retry resets the backoff`() = runTest(dispatcher) {
        repeat(4) { decoders.startFailures += "fake.hw.video/avc" to codecException(1100, transient = false) }
        val p = player(stream = SyntheticStream.flow(paced = true).take(FRAMES * 40))
        p.attachSurface(surface)
        p.mode = TileMode.HW_LIVE
        p.start(this)

        fun hardwareAttempts() = decoders.created.count { it.codecName.startsWith("fake.hw") }

        fun advanceTo(millis: Long) {
            now = millis
            dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS * (FRAMES + 1))
            dispatcher.scheduler.runCurrent()
        }
        advanceTo(0)
        assertThat(hardwareAttempts()).isEqualTo(1) // refused at once, software took over
        advanceTo(TilePlayer.HW_RETRY_MILLIS - 1)
        assertThat(hardwareAttempts()).isEqualTo(1)
        advanceTo(TilePlayer.HW_RETRY_MILLIS)
        assertThat(hardwareAttempts()).isEqualTo(2) // 5 s later: tried again, refused again
        advanceTo(TilePlayer.HW_RETRY_MILLIS + TilePlayer.HW_RETRY_MILLIS * 2 - 1)
        assertThat(hardwareAttempts()).isEqualTo(2) // the second wait is 10 s, not 5
        advanceTo(TilePlayer.HW_RETRY_MILLIS + TilePlayer.HW_RETRY_MILLIS * 2)
        assertThat(hardwareAttempts()).isEqualTo(3)
        advanceTo(now + TilePlayer.HW_RETRY_MILLIS * 4 - 1)
        assertThat(hardwareAttempts()).isEqualTo(3) // 20 s
        p.retry(this) // the user's Retry starts over at 5 s
        advanceTo(now)
        assertThat(hardwareAttempts()).isEqualTo(4)
        assertThat(decoders.created.last().codecName).isEqualTo("fake.sw.video/avc")
        p.stop()
    }

    @Test
    fun `retry forgets a decoder failure and reopens the stream`() = runTest(dispatcher) {
        var opens = 0
        val p =
            TilePlayer(
                camera,
                opener = { _, _, onStatus ->
                    opens++
                    onStatus(StreamStatus.Streaming)
                    SyntheticStream.flow(paced = false).take(FRAMES)
                },
                pool = pool,
                pickCodec = decoders::pickCodec,
                decoderFactory = decoders.factory,
                dispatcher = dispatcher,
                releaser = releaseNow,
            )
        // Software fails, the one hardware attempt fails, software fails again: a tile error.
        decoders.startFailures += "fake.sw.video/avc" to codecException(7, transient = false)
        decoders.startFailures += "fake.hw.video/avc" to codecException(7, transient = false)
        decoders.startFailures += "fake.sw.video/avc" to codecException(7, transient = false)
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        assertThat((p.state.value as TileState.Error).message).isEqualTo(TilePlayer.DECODER_ERROR)
        p.retry(this)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(opens).isEqualTo(2)
        assertThat(decoders.created.last().started).isTrue()
        assertThat(p.state.value).isEqualTo(TileState.Connecting)
        p.stop()
    }

    @Test
    fun `a configure failure is retried at keyframes, then a tile error, never a crash`() = runTest(dispatcher) {
        // The surface's BufferQueue can stay connected to the codec released a moment ago (MiTV,
        // nativeWindowConnect -22); the first refusals are retried, a lasting one dims the tile.
        repeat(TilePlayer.CONFIGURE_RETRIES + 1) {
            decoders.startFailures += "fake.sw.video/avc" to IllegalArgumentException("BufferQueue already connected")
        }
        val p = player(stream = SyntheticStream.flow(paced = false).take(FRAMES * 3))
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        assertThat(decoders.created).hasSize(TilePlayer.CONFIGURE_RETRIES + 1)
        assertThat(decoders.created.all { it.released }).isTrue()
        val error = p.state.value as TileState.Error
        assertThat(error.message).isEqualTo(TilePlayer.DECODER_ERROR)
        assertThat(error.detail).isEqualTo(
            "codec=fake.sw.video/avc error=IllegalArgumentException: BufferQueue already connected",
        )
        p.stop()
    }

    @Test
    fun `a configure failure that clears up is forgotten`() = runTest(dispatcher) {
        decoders.startFailures += "fake.sw.video/avc" to IllegalArgumentException("BufferQueue already connected")
        val p = player(stream = SyntheticStream.flow(paced = false).take(FRAMES * 2))
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        // First attempt refused and released, the second (next keyframe) runs.
        assertThat(decoders.created).hasSize(2)
        assertThat(decoders.created.first().released).isTrue()
        assertThat(decoders.created.last().started).isTrue()
        assertThat(p.state.value).isNotInstanceOf(TileState.Error::class.java)
        p.stop()
    }

    @Test
    fun `codec error with fallback switches to software, without fallback shows an error`() = runTest(dispatcher) {
        val p = player(stream = SyntheticStream.flow(paced = true).take(FRAMES * 2))
        p.attachSurface(surface)
        p.mode = TileMode.HW_LIVE
        p.start(this)
        dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS * 3)
        dispatcher.scheduler.runCurrent()
        val hw = decoders.created.single()
        hw.listener.onError(hw, codecException(1100, transient = true))
        assertThat(hw.released).isTrue()
        dispatcher.scheduler.advanceUntilIdle()
        val sw = decoders.created.last()
        assertThat(sw.codecName).isEqualTo("fake.sw.video/avc")
        sw.listener.onError(sw, codecException(3, transient = false, diagnosticInfo = "d"))
        val error = p.state.value as TileState.Error
        assertThat(error.message).isEqualTo(TilePlayer.DECODER_ERROR)
        assertThat(error.detail).isEqualTo("codec=fake.sw.video/avc error=3 transient=false recoverable=false diag=d")
        p.stop()
    }

    @Test
    fun `hardware is tried again at a keyframe once the cooldown passed`() = runTest(dispatcher) {
        decoders.startFailures += "fake.hw.video/avc" to codecException(1100, transient = false)
        val p = player(stream = SyntheticStream.flow(paced = true).take(FRAMES * 4))
        p.attachSurface(surface)
        p.mode = TileMode.HW_LIVE
        p.start(this)
        dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS * 3)
        dispatcher.scheduler.runCurrent()
        assertThat(decoders.created.map { it.codecName })
            .containsExactly("fake.hw.video/avc", "fake.sw.video/avc")
            .inOrder()
        // Before the cooldown: the software decoder keeps running.
        dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS * 3)
        dispatcher.scheduler.runCurrent()
        assertThat(decoders.created).hasSize(2)
        // After the cooldown the next keyframe rebuilds with hardware, which now succeeds.
        now = TilePlayer.HW_RETRY_MILLIS
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(decoders.created.last().codecName).isEqualTo("fake.hw.video/avc")
        assertThat(decoders.created.last().started).isTrue()
        assertThat(decoders.created[1].released).isTrue()
        p.stop()
    }

    private companion object {
        const val FRAMES = 20
    }
}
