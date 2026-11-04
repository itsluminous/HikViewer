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
import com.itsluminous.hikviewer.fakes.NoopKeyframePool
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

    private fun player(
        stream: Flow<AccessUnit> = SyntheticStream.flow(paced = false).take(FRAMES),
        statusHook: ((StreamStatus) -> Unit) -> Unit = {
            it(StreamStatus.Connecting(1))
            it(StreamStatus.Streaming)
        },
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
    )

    private fun TestScope.run(player: TilePlayer) {
        player.start(this)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `without a surface nothing is decoded`() = runTest(dispatcher) {
        val p = player()
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
        assertThat(decoders.created[1].started).isTrue()
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
    fun `keyframe mode hands keyframes to the pool`() = runTest(dispatcher) {
        val p = player()
        p.attachSurface(surface)
        run(p)
        assertThat(decoders.created).isEmpty()
        assertThat(pool.submitted).isNotEmpty()
        assertThat(pool.submitted.all { it.second.isKeyframe }).isTrue()
        assertThat(p.state.value).isEqualTo(TileState.Keyframe)
        p.stop()
    }

    @Test
    fun `mode change releases the decoder and the next keyframe builds a new one`() = runTest(dispatcher) {
        val p = player(stream = SyntheticStream.flow(paced = true).take(FRAMES * 2))
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        p.start(this)
        dispatcher.scheduler.advanceTimeBy(SyntheticStream.FRAME_PERIOD_MILLIS * 3)
        dispatcher.scheduler.runCurrent()
        val first = decoders.created.first()
        p.mode = TileMode.HW_LIVE
        assertThat(first.released).isTrue()
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(decoders.created.last().codecName).isEqualTo("fake.hw.video/avc")
        p.stop()
    }

    @Test
    fun `surface loss releases the decoder`() = runTest(dispatcher) {
        val p = player()
        p.attachSurface(surface)
        p.mode = TileMode.SW_LIVE
        run(p)
        p.attachSurface(null)
        assertThat(decoders.created.single().released).isTrue()
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
    fun `terminal stream errors map to tile errors`() = runTest(dispatcher) {
        val cases =
            listOf(
                StreamException.WrongVerificationCode() to TileState.Error("Wrong code", wrongVerificationCode = true),
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
