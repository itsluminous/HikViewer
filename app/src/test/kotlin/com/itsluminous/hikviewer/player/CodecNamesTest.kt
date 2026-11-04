package com.itsluminous.hikviewer.player

import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.MediaCodecInfoBuilder

@RunWith(AndroidJUnit4::class)
class CodecNamesTest {
    private fun decoder(
        name: String,
        softwareFlag: Boolean,
    ): MediaCodecInfo = MediaCodecInfoBuilder
        .newBuilder()
        .setName(name)
        .setIsSoftwareOnly(softwareFlag)
        .setIsHardwareAccelerated(!softwareFlag)
        .setCapabilities(
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder
                .newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 960, 480))
                .setIsEncoder(false)
                .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
                .build(),
        ).build()

    @Test
    fun `software decoder names`() {
        assertThat(CodecNames.isSoftwareName("OMX.google.h264.decoder")).isTrue()
        assertThat(CodecNames.isSoftwareName("c2.android.avc.decoder")).isTrue()
        assertThat(CodecNames.isSoftwareName("OMX.MS.AVC.Decoder")).isFalse()
        assertThat(CodecNames.isSoftwareName("c2.goldfish.h264.decoder")).isFalse()
    }

    @Test
    fun `a google decoder the vendor flagged as hardware still counts as software`() {
        // The MiTV ships OMX.google.h264.decoder from /vendor/etc without the software-only flag.
        val codecs =
            listOf(
                decoder("OMX.MS.AVC.Decoder", softwareFlag = false),
                decoder("OMX.google.h264.decoder", softwareFlag = false),
            )
        assertThat(
            CodecNames.pick(MediaFormat.MIMETYPE_VIDEO_AVC, software = true, codecs),
        ).isEqualTo("OMX.google.h264.decoder")
        assertThat(
            CodecNames.pick(MediaFormat.MIMETYPE_VIDEO_AVC, software = false, codecs),
        ).isEqualTo("OMX.MS.AVC.Decoder")
    }

    @Test
    fun `software is never satisfied by a hardware decoder, hardware falls back to software`() {
        val hardwareOnly = listOf(decoder("OMX.MS.AVC.Decoder", softwareFlag = false))
        assertThat(CodecNames.pick(MediaFormat.MIMETYPE_VIDEO_AVC, software = true, hardwareOnly)).isNull()
        val softwareOnly = listOf(decoder("c2.android.avc.decoder", softwareFlag = true))
        assertThat(CodecNames.pick(MediaFormat.MIMETYPE_VIDEO_AVC, software = false, softwareOnly))
            .isEqualTo("c2.android.avc.decoder")
        assertThat(CodecNames.pick(MediaFormat.MIMETYPE_VIDEO_HEVC, software = false, softwareOnly)).isNull()
    }
}
