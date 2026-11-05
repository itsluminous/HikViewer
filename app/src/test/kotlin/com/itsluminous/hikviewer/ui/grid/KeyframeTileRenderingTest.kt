package com.itsluminous.hikviewer.ui.grid

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.SyntheticStream
import com.itsluminous.hikviewer.player.KeyframeDecoderPool
import com.itsluminous.hikviewer.player.StillSink
import com.itsluminous.hikviewer.player.TileMode
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.player.TileState
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CompletableFuture

/**
 * The whole keyframe path from the stream to the pixels on a real tile view: a [TilePlayer] in
 * keyframe mode hands the first IDR to the pool, the pool's picture reaches the tile's [StillSink],
 * and the tile shows a bitmap of its own size that is not blank. Round 5 found twelve-tile grids
 * with the tiles beyond the live budget black on the TV; the pool and the sink were fine (this
 * test), the pictures were wiped in the window by an unclipped neighbour (see [CameraTileView]).
 */
@RunWith(AndroidJUnit4::class)
class KeyframeTileRenderingTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dispatcher = StandardTestDispatcher()
    private val camera = Camera("S", 9, "cam", hasSignal = true, isOnline = true)

    /** Paints the sink-sized picture the real pool would, from the keyframe's bytes, without a codec. */
    private class PaintingPool : KeyframeDecoderPool(maxDecoders = 1, pickCodec = { null }) {
        var jobs = 0

        override fun submit(
            tile: Any,
            sink: StillSink,
            unit: AccessUnit,
        ) {
            if (!unit.isKeyframe) return
            jobs++
            val w = sink.stillWidth
            val h = sink.stillHeight
            if (w <= 0 || h <= 0) return
            val bytes = unit.data
            val green = IntArray(w * h) { 0xFF000000.toInt() or (bytes[it % bytes.size].toInt() and 0xFF shl 8) }
            sink.onStill(green, w, h)
        }
    }

    @Test
    fun `a keyframe tile shows a non-empty still of its own size after the first IDR`() = runTest(dispatcher) {
        val tile = CameraTileView(context)
        tile.measure(
            View.MeasureSpec.makeMeasureSpec(TILE_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(TILE_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        tile.layout(0, 0, TILE_WIDTH, TILE_HEIGHT)
        val pool = PaintingPool()
        val decoders = RecordingDecoderFactory()
        val player =
            TilePlayer(
                camera,
                opener = { _, _, onStatus ->
                    onStatus(StreamStatus.Connecting(1))
                    onStatus(StreamStatus.Streaming)
                    SyntheticStream.flow(paced = false).take(FRAMES)
                },
                pool = pool,
                quality = StreamQuality.SUB,
                pickCodec = decoders::pickCodec,
                decoderFactory = decoders.factory,
                dispatcher = dispatcher,
                releaser = {
                    it.release()
                    CompletableFuture.completedFuture(null)
                },
            )
        val job = tile.attachPlayer(player, this)
        player.mode = TileMode.KEYFRAME
        player.start(this)
        dispatcher.scheduler.advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        assertThat(decoders.created).isEmpty() // keyframe mode owns no decoder
        assertThat(pool.jobs).isEqualTo(SyntheticStream.pictures.take(FRAMES).count { it.isKeyframe })
        assertThat(player.state.value).isEqualTo(TileState.Keyframe)
        val still = tile.findViewById<ImageView>(R.id.still)
        assertThat(still.visibility).isEqualTo(View.VISIBLE)
        val bitmap = (still.drawable as BitmapDrawable).bitmap
        assertThat(bitmap.width).isEqualTo(tile.stillWidth)
        assertThat(bitmap.height).isEqualTo(tile.stillHeight)
        assertThat(bitmap.width).isEqualTo(TILE_WIDTH)
        assertThat(bitmap.config).isEqualTo(Bitmap.Config.RGB_565)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertThat(pixels.any { it and 0x00FFFFFF != 0 }).isTrue() // not a black tile
        // The dot reads "flowing" from the delivered still, not from the keyframe handed to the pool.
        player.tick(0L)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(player.framesFlowing.value).isTrue()
        assertThat(tile.framesFlowing).isTrue()
        job.cancel()
        player.stop()
    }

    private companion object {
        const val TILE_WIDTH = 478
        const val TILE_HEIGHT = 268
        const val FRAMES = 20
    }
}
