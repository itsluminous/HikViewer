package com.itsluminous.hikviewer.player

import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(AndroidJUnit4::class)
class CodecNamesTest {
    private fun decoder(
        name: String,
        softwareFlag: Boolean,
        lowLatency: Boolean = false,
        /** What `getMaxSupportedInstances()` answers; null leaves the builder's platform default (32). */
        maxInstances: Int? = null,
    ): MediaCodecInfo {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 960, 480)
        if (lowLatency) format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency, true)
        val capabilities =
            MediaCodecInfoBuilder.CodecCapabilitiesBuilder
                .newBuilder()
                .setMediaFormat(format)
                .setIsEncoder(false)
                .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
                .build()
        // The builder pins the instance limit to the platform's 32; the field is what a real codec fills in.
        maxInstances?.let { ReflectionHelpers.setField(capabilities, "mMaxSupportedInstances", it) }
        return MediaCodecInfoBuilder
            .newBuilder()
            .setName(name)
            .setIsSoftwareOnly(softwareFlag)
            .setIsHardwareAccelerated(!softwareFlag)
            .setCapabilities(capabilities)
            .build()
    }

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

    /** Round 18: `KEY_LOW_LATENCY` is sent only to a decoder that declares the feature. */
    @Test
    fun `low latency is supported only by a codec that declares the feature`() {
        val codecs =
            listOf(
                decoder("c2.amlogic.avc.decoder", softwareFlag = false, lowLatency = false),
                decoder("c2.android.avc.decoder", softwareFlag = true, lowLatency = true),
            )
        assertThat(CodecNames.supportsLowLatency("c2.amlogic.avc.decoder", codecs)).isFalse()
        assertThat(CodecNames.supportsLowLatency("c2.android.avc.decoder", codecs)).isTrue()
        assertThat(CodecNames.supportsLowLatency("OMX.nowhere.decoder", codecs)).isFalse()
    }

    @Test
    fun `hardware slots come from the hardware decoder's instance limit, two when absent or unknown`() {
        val software = decoder("c2.android.avc.decoder", softwareFlag = true, maxInstances = 16)
        assertThat(
            CodecNames.hardwareSlots(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                listOf(software, decoder("c2.amlogic.avc.decoder", false, maxInstances = 1)),
            ),
        ).isEqualTo(1)
        assertThat(
            CodecNames.hardwareSlots(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                listOf(decoder("OMX.MS.AVC.Decoder", false, maxInstances = 2), software),
            ),
        ).isEqualTo(2)
        // The platform's own default (32, "does not say") and a device without a hardware decoder both read as two.
        assertThat(
            CodecNames.hardwareSlots(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                listOf(decoder("OMX.MS.AVC.Decoder", false), software),
            ),
        ).isEqualTo(2)
        assertThat(CodecNames.hardwareSlots(MediaFormat.MIMETYPE_VIDEO_AVC, listOf(software))).isEqualTo(2)
        assertThat(CodecNames.hardwareSlots(MediaFormat.MIMETYPE_VIDEO_HEVC, listOf(software))).isEqualTo(2)
    }
}
