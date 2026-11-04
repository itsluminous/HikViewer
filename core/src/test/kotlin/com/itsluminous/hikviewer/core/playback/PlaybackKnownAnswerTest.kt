package com.itsluminous.hikviewer.core.playback

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.stream.DecodePipeline
import com.itsluminous.hikviewer.core.stream.NalDecryptor
import com.itsluminous.hikviewer.core.stream.VideoCodec
import org.junit.jupiter.api.Test

/**
 * Known answer from the playback spike: `pb_ch1_winend.h265` is the decrypted output of a 10 s
 * playback window (HEVC Main 1280x720 @ 25 fps). `ffprobe -count_frames` reads 274 frames in 11
 * GOPs. Re-packed as MPEG-PS and run through the live pipeline it must assemble into the same
 * pictures; this pins the HEVC access-unit path playback depends on.
 */
class PlaybackKnownAnswerTest {
    @Test
    fun `the spike capture assembles into 274 hevc pictures in 11 gops`() {
        val pipeline = DecodePipeline(null)
        val units = PlaybackFixture.nals().flatMap { pipeline.process(PlaybackFixture.ps(it)) }

        // The assembler holds the last picture until the next one starts; the stream ends first.
        assertThat(units).hasSize(273)
        assertThat(units.count { it.isKeyframe }).isEqualTo(11)
        assertThat(units.all { it.codec == VideoCodec.H265 }).isTrue()
        assertThat(pipeline.codec).isEqualTo(VideoCodec.H265)
        assertThat(pipeline.variant).isEqualTo(NalDecryptor.Variant.CLEAR)
        val first = units.first()
        assertThat(first.isKeyframe).isTrue()
        val config = checkNotNull(first.config)
        assertThat(config.vps).isNotNull()
        assertThat(config.csd).hasSize(1)
        // Every keyframe opens a GOP of 25 pictures (1 s at 25 fps), except the truncated last one.
        val gopSizes = units.indices.filter { units[it].isKeyframe }.zipWithNext { a, b -> b - a }
        assertThat(gopSizes.toSet()).containsExactly(25)
    }
}
