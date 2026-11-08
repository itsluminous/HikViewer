package com.itsluminous.hikviewer.player

import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.stream.CodecConfig
import com.itsluminous.hikviewer.core.stream.VideoCodec
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TileDecoderFormatTest {
    private val config =
        CodecConfig(
            VideoCodec.H264,
            vps = null,
            sps = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2),
            pps = byteArrayOf(0, 0, 0, 1, 0x68, 3),
        )

    /** Round 18: the low-latency key goes only to a codec that declared the feature (SDK 35 here, so the API gate passes). */
    @Test
    fun `the input format carries the parameter sets and the low latency key only when supported`() {
        val supported = TileDecoder.inputFormat(config, 960 to 480, lowLatency = true)
        assertThat(supported.getString(MediaFormat.KEY_MIME)).isEqualTo(MediaFormat.MIMETYPE_VIDEO_AVC)
        assertThat(supported.getInteger(MediaFormat.KEY_WIDTH)).isEqualTo(960)
        assertThat(supported.getInteger(MediaFormat.KEY_HEIGHT)).isEqualTo(480)
        assertThat(supported.getByteBuffer("csd-0")!!.remaining()).isEqualTo(config.sps.size)
        assertThat(supported.getByteBuffer("csd-1")!!.remaining()).isEqualTo(config.pps.size)
        assertThat(supported.containsKey(MediaFormat.KEY_LOW_LATENCY)).isTrue()
        assertThat(supported.getInteger(MediaFormat.KEY_LOW_LATENCY)).isEqualTo(1)

        val unsupported = TileDecoder.inputFormat(config, 960 to 480, lowLatency = false)
        assertThat(unsupported.containsKey(MediaFormat.KEY_LOW_LATENCY)).isFalse()
        assertThat(unsupported.getByteBuffer("csd-1")).isNotNull()
    }

    /**
     * Round 19: the input buffers are sized from the stream, not from the 960x480 hint. A 1080p
     * HEVC keyframe of the main stream is 100 KB and more; a codec that sized its buffers from the
     * hint would make every such picture oversized.
     */
    @Test
    fun `the input format asks for input buffers of the given size`() {
        val format = TileDecoder.inputFormat(config, lowLatency = false, maxInputSize = 3 shl 20)
        assertThat(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)).isEqualTo(3 shl 20)

        val default = TileDecoder.inputFormat(config, lowLatency = false)
        assertThat(default.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)).isEqualTo(TileDecoder.DEFAULT_MAX_INPUT_SIZE)
    }

    @Test
    fun `the input size is twice the keyframe, floored at 1 MiB and capped at 8 MiB`() {
        assertThat(TileDecoder.inputSizeFor(105_953)).isEqualTo(1 shl 20) // the 2.Jaimaal 1080p HEVC IDR
        assertThat(TileDecoder.inputSizeFor(14_000)).isEqualTo(1 shl 20) // a sub-stream keyframe
        assertThat(TileDecoder.inputSizeFor(700_000)).isEqualTo(1_400_000)
        assertThat(TileDecoder.inputSizeFor(6 shl 20)).isEqualTo(8 shl 20)
        assertThat(TileDecoder.inputSizeFor(Int.MAX_VALUE)).isEqualTo(8 shl 20)
    }

    @Test
    fun `a picture is queued only when it fits the input buffer whole, never cut`() {
        assertThat(TileDecoder.fits(size = 1000, capacity = 1000)).isTrue()
        assertThat(TileDecoder.fits(size = 1001, capacity = 1000)).isFalse()
        assertThat(TileDecoder.fits(size = 0, capacity = 1000)).isTrue()
    }
}
