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
}
