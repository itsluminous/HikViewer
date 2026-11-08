package com.itsluminous.hikviewer.core.playback

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.DecodePipeline
import com.itsluminous.hikviewer.core.stream.NalDecryptor
import com.itsluminous.hikviewer.core.stream.VideoCodec
import com.itsluminous.hikviewer.core.testutil.Fixtures
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Known answer for the recorder's real MPEG-PS shape (round 19): `synthetic_pb_slices.ps` is a
 * 4-slice HEVC 1280x720 clip (10 pictures, an IDR of about 258 KB every 5) muxed the way the DVR
 * ships recordings, every NAL in a chain of bounded PES packets of at most 63989 payload bytes with
 * the PTS only on the first PES of a picture, IRAP slices and parameter sets AES-encrypted; see
 * `scripts/gen-fixtures.sh`, which prints the numbers pinned here. A slice bigger than a PES is
 * split over two of them, which is what cut the bottom off every keyframe of the 2.Jaimaal
 * recording before the demuxer learnt that a PES end is not a NAL end. Demux, decrypt and assemble
 * must give back every slice of every picture, byte for byte.
 */
class PlaybackSlicesKnownAnswerTest {
    private val ps = Fixtures.bytes("synthetic_pb_slices.ps")

    @Test
    fun `every slice of every picture survives demux, decryption and assembly`() {
        for (chunk in listOf(1400, 6000, ps.size)) {
            val pipeline = DecodePipeline(KEY)
            val units = ArrayList<AccessUnit>()
            var offset = 0
            while (offset < ps.size) {
                val end = minOf(offset + chunk, ps.size)
                units += pipeline.process(ps.copyOfRange(offset, end))
                offset = end
            }
            units += pipeline.flush()

            assertThat(pipeline.codec).isEqualTo(VideoCodec.H265)
            assertThat(pipeline.variant).isEqualTo(NalDecryptor.Variant.HEADER_CLEAR)
            assertThat(pipeline.discardedBytes).isEqualTo(0)
            assertThat(units.map { it.size }).containsExactlyElementsIn(PICTURE_SIZES).inOrder()
            val keyframes = PICTURE_SIZES.map { it > 200_000 }
            assertThat(units.map { it.isKeyframe }).containsExactlyElementsIn(keyframes).inOrder()
            assertThat(units.map { slices(it) }).containsExactlyElementsIn(List(PICTURE_SIZES.size) { 4 }).inOrder()
            assertThat(units.maxOf { it.size }).isGreaterThan(200_000)
            assertThat(units.map { it.presentationTimeUs }).isEqualTo(List(PICTURE_SIZES.size) { (it + 1) * 40_000L })
            val digest = MessageDigest.getInstance("SHA-256")
            units.forEach { digest.update(it.data) }
            assertThat(digest.digest().joinToString("") { "%02x".format(it) }).isEqualTo(CLEAR_SHA256)
        }
    }

    /** VCL NALs in the picture (types 0 to 31 after a 4-byte start code). */
    private fun slices(unit: AccessUnit): Int {
        var count = 0
        var i = 0
        val d = unit.data
        while (i + 4 < d.size) {
            if (d[i] == 0.toByte() && d[i + 1] == 0.toByte() && d[i + 2] == 0.toByte() && d[i + 3] == 1.toByte()) {
                if (VideoCodec.H265.isVcl(VideoCodec.H265.nalType(d[i + 4]))) count++
                i += 4
            } else {
                i++
            }
        }
        return count
    }

    private companion object {
        /** `gen-fixtures.sh`'s dummy verification code, shared with `NalDecryptorTest`. */
        const val KEY = "ABCDEF1234"
        val PICTURE_SIZES = listOf(259066, 6942, 6684, 6521, 6491, 259066, 6942, 6684, 6521, 6491)
        const val CLEAR_SHA256 = "4218de8f8b7785e90912629cad1b8495825904218999e47fed346712dcbf6f7c"
    }
}
