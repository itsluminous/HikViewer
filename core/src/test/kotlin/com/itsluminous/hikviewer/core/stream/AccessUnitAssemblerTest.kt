package com.itsluminous.hikviewer.core.stream

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.testutil.Fixtures
import org.junit.jupiter.api.Test

class AccessUnitAssemblerTest {
    private val start = byteArrayOf(0, 0, 0, 1)

    @Test
    fun `real capture assembles into 31 pictures with 3 keyframes and csd`() {
        val demuxer = TsDemuxer()
        val nals = demuxer.feed(Fixtures.bytes("ch1_sub_2s5.ts")) + demuxer.flush()
        val assembler = AccessUnitAssembler(VideoCodec.H264)

        val units = nals.mapNotNull { assembler.feed(it) } + listOfNotNull(assembler.flush())

        assertThat(units).hasSize(31)
        assertThat(units.count { it.isKeyframe }).isEqualTo(3)
        assertThat(units.first().isKeyframe).isTrue()
        assertThat(units.first().presentationTimeUs).isEqualTo(126000L * 1_000_000 / 90_000)
        assertThat(units[1].presentationTimeUs!! - units[0].presentationTimeUs!!).isEqualTo(83_333)
        assertThat(units.sumOf { it.size }).isEqualTo(nals.sumOf { it.size + 4 })
        val config = units.first().config!!
        assertThat(config.codec).isEqualTo(VideoCodec.H264)
        assertThat(config.vps).isNull()
        assertThat(config.sps.toList().take(5)).isEqualTo(listOf<Byte>(0, 0, 0, 1, 0x67))
        assertThat(config.pps.toList().take(5)).isEqualTo(listOf<Byte>(0, 0, 0, 1, 0x68))
        assertThat(config.csd).hasSize(2)
        assertThat(units.all { it.config == config }).isTrue()
        // Each keyframe AU carries AUD + SPS + PPS + IDR, each P AU carries AUD + slice.
        assertThat(
            units
                .first()
                .data
                .toList()
                .windowed(5)
                .count { it == listOf<Byte>(0, 0, 0, 1, 0x67) },
        ).isEqualTo(1)
        assertThat(units.first().toString()).contains("key=true")
    }

    @Test
    fun `without timestamps the first-slice flag splits pictures`() {
        val assembler = AccessUnitAssembler(VideoCodec.H264)
        val slice1 = byteArrayOf(0x41, 0x9A.toByte(), 1) // first_mb_in_slice = 0 (leading 1 bit)
        val slice2 = byteArrayOf(0x41, 0x1A, 2) // continuation slice of the same picture
        val units = listOf(slice1, slice2, slice1, slice1).mapNotNull { assembler.feed(NalUnit(it, null)) }

        assertThat(units).hasSize(2)
        assertThat(units[0].data).isEqualTo(start + slice1 + start + slice2)
        assertThat(units[0].presentationTimeUs).isNull()
        assertThat(units[1].data).isEqualTo(start + slice1)
        assertThat(assembler.flush()!!.data).isEqualTo(start + slice1)
        assertThat(assembler.flush()).isNull()
    }

    @Test
    fun `non-VCL NALs after slices start the next picture and hevc config needs the VPS`() {
        val assembler = AccessUnitAssembler(VideoCodec.H265)
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())
        val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte())
        val trail = byteArrayOf(0x02, 0x01, 0xD0.toByte())
        val sei = byteArrayOf(0x4E, 0x01, 0x05)

        assertThat(assembler.feed(NalUnit(sps, 1))).isNull()
        assertThat(assembler.config).isNull()
        assertThat(assembler.feed(NalUnit(pps, 1))).isNull()
        assertThat(assembler.config).isNull() // HEVC needs VPS for csd-0
        assertThat(assembler.feed(NalUnit(vps, 1))).isNull()
        assertThat(assembler.config!!.csd.single()).isEqualTo(start + vps + start + sps + start + pps)
        assertThat(assembler.feed(NalUnit(idr, 1))).isNull()
        val first = assembler.feed(NalUnit(sei, 2))!! // prefix SEI after a slice opens the next AU
        assertThat(first.isKeyframe).isTrue()
        assertThat(first.codec).isEqualTo(VideoCodec.H265)
        assertThat(assembler.feed(NalUnit(trail, 2))).isNull()
        val second = assembler.feed(NalUnit(trail, 3))!!
        assertThat(second.isKeyframe).isFalse()
        assertThat(second.data).isEqualTo(start + sei + start + trail)
        assertThat(second.presentationTimeUs).isEqualTo(2L * 1_000_000 / 90_000)
    }

    @Test
    fun `rtp timestamps are unwrapped across the 32-bit boundary`() {
        val assembler = AccessUnitAssembler(VideoCodec.H264)
        val slice = byteArrayOf(0x41, 0x9A.toByte())
        val near = (1L shl 32) - 1000
        assembler.feed(NalUnit(slice, near))
        val a = assembler.feed(NalUnit(slice, 500))!! // wrapped
        val b = assembler.feed(NalUnit(slice, 2000))!!
        val c = assembler.flush()!!

        assertThat(a.presentationTimeUs).isEqualTo(near * 1_000_000 / 90_000)
        assertThat(b.presentationTimeUs).isEqualTo(((1L shl 32) + 500) * 1_000_000 / 90_000)
        assertThat(c.presentationTimeUs).isEqualTo(((1L shl 32) + 2000) * 1_000_000 / 90_000)
    }

    @Test
    fun `oversized pictures are cut and empty NALs ignored`() {
        val assembler = AccessUnitAssembler(VideoCodec.H264)
        assertThat(assembler.feed(NalUnit(ByteArray(0), 1))).isNull()
        val huge = NalUnit(byteArrayOf(0x41, 0x1A) + ByteArray(3 shl 20), 1)
        assertThat(assembler.feed(huge)).isNotNull()
        assertThat(assembler.flush()).isNull()
    }

    @Test
    fun `codec config equality`() {
        val a = CodecConfig(VideoCodec.H264, null, byteArrayOf(1), byteArrayOf(2))
        val b = CodecConfig(VideoCodec.H264, null, byteArrayOf(1), byteArrayOf(2))
        val c = CodecConfig(VideoCodec.H265, byteArrayOf(0), byteArrayOf(1), byteArrayOf(2))
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(c)
        assertThat(c.csd.single()).isEqualTo(byteArrayOf(0, 1, 2))
    }
}
