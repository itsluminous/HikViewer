package com.itsluminous.hikviewer.core.stream

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class H264SpsRewriterTest {
    /** The recorder's real sub-stream SPS: High 3.1, VUI with timing info, no bitstream_restriction. */
    private val recorderSps =
        hex("6764001fac2c6b83c0f69b808080a00001d4c0002bf21080")

    /** An x264 SPS (the synthetic fixture): has emulation prevention bytes and a bitstream_restriction. */
    private val x264Sps =
        hex("6764001facb40781ed8088000003000800000300c078c195")

    @Test
    fun `adds a bitstream restriction with no reordering to the recorder's SPS`() {
        val out = H264SpsRewriter.withoutReordering(recorderSps)
        assertThat(out).isNotEqualTo(recorderSps)
        // Everything before vui_parameters_present_flag (bit 80 → first 10 bytes) is untouched.
        assertThat(out.copyOf(10)).isEqualTo(recorderSps.copyOf(10))
        val vui = SpsReader(out).vui()
        assertThat(vui.timingInfoPresent).isTrue()
        assertThat(vui.numUnitsInTick).isEqualTo(3750)
        assertThat(vui.timeScale).isEqualTo(90000)
        assertThat(vui.bitstreamRestriction).isTrue()
        assertThat(vui.maxNumReorderFrames).isEqualTo(0)
        assertThat(vui.maxDecFrameBuffering).isEqualTo(2) // = max_num_ref_frames of this SPS
        assertThat(vui.trailingBitsOk).isTrue()
    }

    @Test
    fun `replaces an existing restriction and is idempotent`() {
        val once = H264SpsRewriter.withoutReordering(x264Sps)
        val twice = H264SpsRewriter.withoutReordering(once)
        assertThat(twice).isEqualTo(once)
        val vui = SpsReader(once).vui()
        assertThat(vui.bitstreamRestriction).isTrue()
        assertThat(vui.maxNumReorderFrames).isEqualTo(0)
        assertThat(vui.trailingBitsOk).isTrue()
        // The escaped payload has no raw 00 00 00..02 sequence (00 00 03 is the escape itself).
        for (i in 2 until once.size) {
            val raw = once[i - 2].toInt() == 0 && once[i - 1].toInt() == 0 && once[i].toInt() in 0..2
            assertThat(raw).isFalse()
        }
        assertThat(H264SpsRewriter.withoutReordering(recorderSps)).isEqualTo(
            H264SpsRewriter.withoutReordering(H264SpsRewriter.withoutReordering(recorderSps)),
        )
    }

    @Test
    fun `adds a VUI when the SPS has none`() {
        // Baseline SPS, 16x16, no VUI: profile 66, level 10, sps_id 0, log2_max_frame_num_minus4 0,
        // poc type 0 (lsb 0), 1 ref frame, no gaps, 1x1 MBs, frame_mbs_only, no direct8x8, no crop, vui 0.
        // Bits: 1 1 1 1 010 0 1 1 1 0 0 0 then the stop bit 1 → f4 e2.
        val sps = hex("42000af4e2")
        val out = H264SpsRewriter.withoutReordering(hex("67") + sps)
        val vui = SpsReader(out).vui()
        assertThat(vui.bitstreamRestriction).isTrue()
        assertThat(vui.maxNumReorderFrames).isEqualTo(0)
        assertThat(vui.maxDecFrameBuffering).isEqualTo(1)
    }

    @Test
    fun `leaves other NALs and unparsable data alone`() {
        val pps = hex("68ee3cb0")
        assertThat(H264SpsRewriter.withoutReordering(pps)).isSameInstanceAs(pps)
        val truncated = recorderSps.copyOf(6)
        assertThat(H264SpsRewriter.withoutReordering(truncated)).isSameInstanceAs(truncated)
        assertThat(H264SpsRewriter.withoutReordering(ByteArray(0))).isEmpty()
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class Vui(
        val timingInfoPresent: Boolean,
        val numUnitsInTick: Long,
        val timeScale: Long,
        val bitstreamRestriction: Boolean,
        val maxNumReorderFrames: Int,
        val maxDecFrameBuffering: Int,
        val trailingBitsOk: Boolean,
    )

    /** Independent, minimal SPS reader for the fields the rewriter touches (no scaling lists, poc type 0/2 only). */
    private class SpsReader(
        nal: ByteArray,
    ) {
        private val data = unescape(nal)
        private var pos = 8 // skip the NAL header

        fun vui(): Vui {
            skipToVui()
            if (bit() == 1 && bits(8) == 255L) bits(32)
            if (bit() == 1) bit()
            if (bit() == 1) {
                bits(4)
                if (bit() == 1) bits(24)
            }
            if (bit() == 1) {
                ue()
                ue()
            }
            val timing = bit() == 1
            var units = 0L
            var scale = 0L
            if (timing) {
                units = bits(32)
                scale = bits(32)
                bit()
            }
            check(bit() == 0 && bit() == 0) { "hrd not supported here" }
            bit()
            val restriction = bit() == 1
            var reorder = -1
            var buffering = -1
            if (restriction) {
                bit()
                ue()
                ue()
                ue()
                ue()
                reorder = ue()
                buffering = ue()
            }
            val stop = bit() == 1
            var zeros = true
            while (pos < data.size * 8) if (bit() != 0) zeros = false
            return Vui(timing, units, scale, restriction, reorder, buffering, stop && zeros)
        }

        private fun skipToVui() {
            val profile = bits(8)
            bits(8)
            bits(8)
            ue()
            if (profile in listOf(100L, 110L, 122L, 244L, 44L, 83L, 86L, 118L, 128L, 138L, 139L, 134L, 135L)) {
                val chroma = ue()
                if (chroma == 3) bit()
                ue()
                ue()
                bit()
                check(bit() == 0) { "scaling lists not supported here" }
            }
            ue()
            when (ue()) {
                0 -> ue()
                1 -> error("poc type 1 not supported here")
            }
            ue()
            bit()
            ue()
            ue()
            if (bit() == 0) bit()
            bit()
            if (bit() == 1) repeat(4) { ue() }
            check(bit() == 1) { "no VUI" }
        }

        private fun bit(): Int {
            val b = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return b
        }

        private fun bits(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 1) or bit().toLong() }
            return v
        }

        private fun ue(): Int {
            var zeros = 0
            while (bit() == 0) zeros++
            return ((1L shl zeros) - 1 + bits(zeros)).toInt()
        }

        private fun unescape(nal: ByteArray): ByteArray {
            val out = ArrayList<Byte>()
            var zeros = 0
            for (b in nal) {
                val v = b.toInt() and 0xFF
                if (zeros >= 2 && v == 3) {
                    zeros = 0
                    continue
                }
                out += b
                zeros = if (v == 0) zeros + 1 else 0
            }
            return out.toByteArray()
        }
    }
}
