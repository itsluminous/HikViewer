package com.itsluminous.hikviewer.player

import android.media.MediaCodec
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.StreamException
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.fakes.DeliveringKeyframePool
import com.itsluminous.hikviewer.fakes.NoopKeyframePool
import com.itsluminous.hikviewer.fakes.RecordingDecoder
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.SyntheticStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow

@RunWith(AndroidJUnit4::class)
class TilePlayerTest {
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
    fun `without a surface no live decoder is built`() = runTest(dispatcher) {
        val p = player()
        p.mode = TileMode.SW_LIVE
        run(p)
        assertThat(decoders.created).isEmpty()
        assertThat(p.state.value).isEqualTo(TileState.Connecting)
        p.stop()
    }

    @Test
    fun `live mode creates a decoder at the first keyframe and feeds every picture`() = runTest(dispatcher) {
        val p = player()
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        val d = decoders.created.single()
        assertThat(d.codecName).isEqualTo("fake.sw.video/avc")
        assertThat(d.started).isTrue()
        assertThat(d.renderSlotNanos).isEqualTo(RenderPacer.DEFAULT_SLOT_NANOS) // software tiles share a cadence
        assertThat(d.fed.first().isKeyframe).isTrue()
        assertThat(d.fed.size).isEqualTo(FRAMES - SyntheticStream.pictures.indexOfFirst { it.isKeyframe })
        assertThat(p.state.value).isEqualTo(TileState.Connecting)
        d.emitFirstFrame()
        assertThat(p.state.value).isEqualTo(TileState.Live)
        p.stop()
        assertThat(d.released).isTrue()
    }

    @Test
    fun `hardware mode asks for a hardware codec and falls back to software on error 1100`() = runTest(dispatcher) {
        decoders.startFailures += "fake.hw.video/avc" to codecException(1100, transient = false)
        val p = player()
        p.attachSurface(surface)
        p.mode = TileMode.HW_LIVE
        run(p)
        assertThat(
            decoders.created.map { it.codecName },
        ).containsExactly("fake.hw.video/avc", "fake.sw.video/avc").inOrder()
        assertThat(decoders.created[0].released).isTrue()
        assertThat(decoders.created[0].renderSlotNanos).isEqualTo(0L) // hardware renders at once
        assertThat(decoders.created[1].started).isTrue()
        assertThat(decoders.created[1].renderSlotNanos).isEqualTo(RenderPacer.DEFAULT_SLOT_NANOS)
        p.stop()
    }

    @Test
    fun `a non-transient start failure is an error state`() = runTest(dispatcher) {
        decoders.startFailures += "fake.hw.video/avc" to codecException(7, transient = false)
        val p = player()
        p.attachSurface(surface)
        p.mode = TileMode.HW_LIVE
        run(p)
        assertThat(decoders.created).hasSize(1)
        assertThat(p.state.value).isEqualTo(TileState.Error("Decoder error"))
        p.stop()
    }

    @Test
    fun `frame rate is sampled on tick from the decoder, and from delivered stills in keyframe mode`() =
        runTest(dispatcher) {
            val p = player()
            p.attachSurface(surface)
            p.mode = TileMode.SW_LIVE
            run(p)
            assertThat(p.frameRate.value).isEqualTo(0f)
            decoders.created.single().fps = 24.6f
            p.tick(1_000)
            assertThat(p.frameRate.value).isEqualTo(25f) // whole frames, as the counter shows them
            p.stop()
            assertThat(p.frameRate.value).isEqualTo(0f)

            val delivering = DeliveringKeyframePool()
            val k = player(pool = delivering)
            k.attachSurface(surface)
            now = 10_000
            run(k) // KEYFRAME mode: every still the pool delivers at `now` counts as one picture
            val stills = delivering.delivered.size
            assertThat(stills).isGreaterThan(0)
            k.tick(now + FrameRateMeter.DEFAULT_WINDOW_MILLIS - 1)
            assertThat(k.frameRate.value)
                .isEqualTo(Math.round(stills * 1_000f / FrameRateMeter.DEFAULT_WINDOW_MILLIS).toFloat())
            k.stop()

            // A pool that never delivers (its decoder keeps failing) renders no picture: 0 fps.
            val starved = player()
            starved.attachSurface(surface)
            run(starved)
            assertThat(pool.submitted).isNotEmpty()
            starved.tick(now + 500)
            assertThat(starved.frameRate.value).isEqualTo(0f)
            starved.stop()
        }

    @Test
    fun `frames flowing follows the decoder's last output within two seconds, and stills in keyframe mode`() =
        runTest(dispatcher) {
            val p = player()
            p.attachSurface(surface)
            p.mode = TileMode.SW_LIVE
            run(p)
            val d = decoders.created.single()
            assertThat(p.framesFlowing.value).isFalse() // no picture has come out yet
            d.outputAge = 100
            d.emitFirstFrame()
            p.tick(1_000)
            assertThat(p.framesFlowing.value).isTrue()
            d.outputAge = TilePlayer.FLOWING_MILLIS + 1
            p.tick(4_000)
            assertThat(p.framesFlowing.value).isFalse()
            d.outputAge = 500
            p.tick(4_500)
            assertThat(p.framesFlowing.value).isTrue()
            p.stop()
            assertThat(p.framesFlowing.value).isFalse()

            // KEYFRAME mode: the dot follows the stills the pool delivers, not the keyframes handed to it.
            val delivering = DeliveringKeyframePool()
            val k = player(pool = delivering)
            k.attachSurface(surface)
            now = 10_000
            run(k) // stills delivered at `now`
            assertThat(delivering.delivered).isNotEmpty()
            k.tick(now + 1_000)
            assertThat(k.framesFlowing.value).isTrue()
            k.tick(now + TilePlayer.FLOWING_MILLIS + 1)
            assertThat(k.framesFlowing.value).isFalse()
            k.stop()
            assertThat(k.framesFlowing.value).isFalse()
        }

    @Test
    fun `a keyframe tile whose pool delivers nothing shows a red dot however many keyframes it submits`() =
        runTest(dispatcher) {
            val k = player() // NoopKeyframePool: swallows every job, like a pool whose decoder keeps failing
            k.attachSurface(surface)
            now = 10_000
            run(k)
            assertThat(pool.submitted).isNotEmpty()
            assertThat(k.state.value).isEqualTo(TileState.Keyframe)
            k.tick(now + 500)
            assertThat(k.framesFlowing.value).isFalse()
            k.tick(now + TilePlayer.FLOWING_MILLIS + 1)
            assertThat(k.framesFlowing.value).isFalse()
            k.stop()
        }

    @Test
    fun `keyframe mode hands keyframes to the pool for the tile's still sink, never a surface`() = runTest(dispatcher) {
        val p = player()
        run(p) // no surface attached: thumbnails need none
        assertThat(decoders.created).isEmpty()
        assertThat(pool.submitted).isNotEmpty()
        assertThat(pool.submitted.all { it.second.isKeyframe }).isTrue()
        assertThat(p.state.value).isEqualTo(TileState.Keyframe)
        p.stop()
        pool.submitted.clear()
        val noSink = player()
        noSink.attachStillSink(null)
        noSink.attachSurface(surface)
        run(noSink)
        assertThat(pool.submitted).isEmpty()
        noSink.stop()
    }

    @Test
    fun `the default releaser tears decoders down on the release thread, never the caller's`() = runTest(dispatcher) {
        val p = player(releaser = DecoderReleaser::release)
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        val d = decoders.created.single()
        p.mode = TileMode.KEYFRAME // the call a focus change makes on the main thread
        val deadline = System.currentTimeMillis() + 5_000
        while (!d.released && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertThat(d.released).isTrue()
        assertThat(d.releasedOn!!.name).isEqualTo(DecoderReleaser.THREAD_NAME)
        assertThat(d.releasedOn).isNotSameInstanceAs(Thread.currentThread())
        p.stop()
    }

    @Test
    fun `a rebuild on the stream thread releases the old decoder before starting the new one`() = runTest(dispatcher) {
        val p = player(stream = SyntheticStream.flow(paced = true).take(FRAMES * 2))
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        p.start(this)
        dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS * 3)
        dispatcher.scheduler.runCurrent()
        val first = decoders.created.single()
        first.stalled = true
        p.tick(10_000) // detaches; the old decoder is torn down (synchronously here)
        assertThat(first.released).isTrue()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(decoders.created).hasSize(2)
        assertThat(decoders.created.last().started).isTrue()
        assertThat(p.decodersCreated).isEqualTo(2)
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
    fun `live to live mode change keeps the decoder until the next keyframe, then swaps`() = runTest(dispatcher) {
        val p = player(stream = SyntheticStream.flow(paced = true).take(FRAMES * 2))
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        p.start(this)
        dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS * 3)
        dispatcher.scheduler.runCurrent()
        val first = decoders.created.single()
        first.emitFirstFrame()
        p.mode = TileMode.HW_LIVE
        // The picture must not go dark: the software decoder keeps running and eating frames.
        assertThat(first.released).isFalse()
        assertThat(p.state.value).isEqualTo(TileState.Live)
        val fedBefore = first.fed.size
        dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS)
        dispatcher.scheduler.runCurrent()
        assertThat(first.fed.size).isAtLeast(fedBefore)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(first.released).isTrue()
        val second = decoders.created.last()
        assertThat(second.codecName).isEqualTo("fake.hw.video/avc")
        assertThat(second.fed.first().isKeyframe).isTrue()
        assertThat(decoders.created).hasSize(2)
        p.stop()
    }

    @Test
    fun `live to keyframe releases the decoder at once, keyframe to live builds one at the next keyframe`() =
        runTest(dispatcher) {
            val p = player(stream = SyntheticStream.flow(paced = true).take(FRAMES * 2))
            p.attachSurface(surface)
            p.mode = TileMode.SW_LIVE
            p.start(this)
            dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS * 3)
            dispatcher.scheduler.runCurrent()
            val first = decoders.created.single()
            first.emitFirstFrame()
            p.mode = TileMode.KEYFRAME
            assertThat(first.released).isTrue()
            assertThat(p.state.value).isEqualTo(TileState.Keyframe)
            p.mode = TileMode.SW_LIVE
            // Still the last keyframe on screen, no spinner, until the live decoder draws.
            assertThat(p.state.value).isEqualTo(TileState.Keyframe)
            dispatcher.scheduler.advanceUntilIdle()
            assertThat(decoders.created).hasSize(2)
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
        decoders.startFailures += "fake.sw.video/avc" to codecException(7, transient = false)
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        assertThat(p.state.value).isEqualTo(TileState.Error("Decoder error"))
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
        assertThat(p.state.value).isEqualTo(TileState.Error("Decoder error"))
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
        assertThat(p.state.value).isNotEqualTo(TileState.Error("Decoder error"))
        p.stop()
    }

    @Test
    fun `surface loss releases the decoder on the spot and waits for releases still in flight`() = runTest(dispatcher) {
        val blocking = java.util.concurrent.CompletableFuture<Unit>()
        var deferred: RecordingDecoder? = null
        val p =
            player(
                stream = SyntheticStream.flow(paced = true).take(FRAMES * 2),
                releaser = { d ->
                    deferred = d as RecordingDecoder
                    blocking // pretends the release thread has not got to it yet
                },
            )
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        p.start(this)
        dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS * 3)
        dispatcher.scheduler.runCurrent()
        val first = decoders.created.single()
        first.stalled = true
        p.tick(10_000) // detached, handed to the (stuck) releaser
        assertThat(deferred).isSameInstanceAs(first)
        assertThat(first.released).isFalse()
        dispatcher.scheduler.advanceUntilIdle()
        val second = decoders.created.last()
        assertThat(second).isNotSameInstanceAs(first)
        // surfaceDestroyed: the current decoder goes synchronously and the stuck release is waited for.
        val waiter = Thread { p.attachSurface(null) }
        waiter.start()
        waiter.join(500)
        assertThat(waiter.isAlive).isTrue() // still waiting on the in-flight release
        assertThat(second.released).isTrue()
        assertThat(second.releasedOn).isSameInstanceAs(waiter)
        first.release()
        blocking.complete(Unit)
        waiter.join(5_000)
        assertThat(waiter.isAlive).isFalse()
        p.stop()
    }

    @Test
    fun `stalled decoder is recreated by the watchdog`() = runTest(dispatcher) {
        val p = player()
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        val d = decoders.created.single()
        d.queued = 4
        assertThat(p.queuedFrames).isEqualTo(4)
        p.tick(1_000)
        assertThat(d.released).isFalse()
        d.stalled = true
        p.tick(5_000)
        assertThat(d.released).isTrue()
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
        sw.listener.onError(sw, codecException(3, transient = false))
        assertThat(p.state.value).isEqualTo(TileState.Error("Decoder error"))
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

    @Test
    fun `terminal stream errors map to tile errors`() = runTest(dispatcher) {
        val cases =
            listOf(
                StreamException.WrongVerificationCode() to
                    TileState.Error("Cannot decrypt", wrongVerificationCode = true),
                StreamException.ChannelOffline(camera) to TileState.Error("No video"),
                StreamException.ChannelNotStreamable(camera) to TileState.Error("No cloud stream"),
                StreamException.RelayRejected(9) to TileState.Error("Relay refused"),
                java.io.IOException("x") to TileState.Error("Offline"),
                IllegalStateException("x") to TileState.Error("Error"),
            )
        for ((exception, expected) in cases) {
            val p = player(stream = flow { throw exception }, statusHook = {})
            run(p)
            assertThat(p.state.value).isEqualTo(expected)
            p.stop()
        }
    }

    @Test
    fun `reconnecting resets the decoder and shows connecting`() = runTest(dispatcher) {
        var status: ((StreamStatus) -> Unit)? = null
        val p = player(statusHook = { status = it })
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        val d = decoders.created.single()
        d.emitFirstFrame()
        assertThat(p.state.value).isEqualTo(TileState.Live)
        status!!(StreamStatus.Reconnecting(1, 1_000, java.io.IOException("x")))
        assertThat(d.released).isTrue()
        assertThat(p.state.value).isEqualTo(TileState.Connecting)
        p.stop()
    }

    private fun codecException(
        code: Int,
        transient: Boolean,
    ): MediaCodec.CodecException {
        val e = Shadow.newInstanceOf(MediaCodec.CodecException::class.java)
        org.robolectric.util.ReflectionHelpers
            .setField(e, "mErrorCode", code)
        org.robolectric.util.ReflectionHelpers.setField(
            e,
            "mActionCode",
            if (transient) ACTION_TRANSIENT else 0,
        )
        return e
    }

    private companion object {
        const val FRAMES = 20

        // MediaCodec.CodecException.ACTION_TRANSIENT (hidden constant).
        const val ACTION_TRANSIENT = 1
    }
}
