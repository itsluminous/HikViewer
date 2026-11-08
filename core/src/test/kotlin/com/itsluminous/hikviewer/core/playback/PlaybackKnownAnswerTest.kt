package com.itsluminous.hikviewer.core.playback

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.stream.DecodePipeline
import com.itsluminous.hikviewer.core.stream.NalDecryptor
import com.itsluminous.hikviewer.core.stream.VideoCodec
import org.junit.jupiter.api.Test

/**
 * Known answer for the HEVC path: `synthetic_pb_winend.h265` is ffmpeg's `testsrc` encoded by x265 to
 * the shape of a decrypted playback window (HEVC Main 1280x720 @ 25 fps, closed GOPs of 25 with a
 * VPS/SPS/PPS before every IDR, no AUDs), see `scripts/gen-fixtures.sh`. `ffprobe -count_frames` reads
 * 274 frames in 11 GOPs, the last one truncated to 24 as a window end cuts a recording mid-GOP.
 * Re-packed as MPEG-PS and run through the live pipeline it must assemble into the same pictures (all 274
 * once the window end flushes the pipeline);
 * this pins the HEVC access-unit path playback depends on.
 */
class PlaybackKnownAnswerTest {
    @Test
    fun `the synthetic window assembles into 274 hevc pictures in 11 gops`() {
        val pipeline = DecodePipeline(null)
        val streamed = PlaybackFixture.nals().flatMap { pipeline.process(PlaybackFixture.ps(it)) }
        // The demuxer holds the last NAL for the next start code and the assembler the last picture
        // for the next one (the fixture's PES carry no PTS); the window end releases both.
        assertThat(streamed).hasSize(272)
        val units = streamed + pipeline.flush()

        assertThat(units).hasSize(274)
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
