package com.itsluminous.hikviewer.core.stream

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.testutil.Fixtures
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DemuxerTest {
    /**
     * Known-answer test against `synthetic_sub_2s5.ts`: 31 pictures of ffmpeg's `testsrc2` encoded by x264
     * to the sub-stream's shape (H.264 High 960x480 @ 12 fps, an IDR every 12 pictures, MPEG-TS), see
     * `scripts/gen-fixtures.sh`. Expected values from ffprobe:
     * `-show_packets` → 31 packets, 3 with the K flag, first pts 126000;
     * `-show_frames` → 3 I + 28 P; NAL histogram of `-c copy -f h264` → AUD 31, SPS 3, PPS 3, IDR 3, non-IDR 28.
     */
    @Nested
    inner class TransportStream {
        private val ts = Fixtures.bytes("synthetic_sub_2s5.ts")

        @Test
        fun `known answer from the synthetic clip, fed in relay-sized chunks`() {
            val demuxer = AutoDemuxer()
            val nals = ArrayList<NalUnit>()
            var offset = 0
            while (offset < ts.size) {
                val end = minOf(offset + 7 * 188, ts.size)
                nals += demuxer.feed(ts.copyOfRange(offset, end))
                offset = end
            }
            nals += demuxer.flush()

            assertThat(demuxer.container).isEqualTo(AutoDemuxer.Container.MPEG_TS)
            assertThat(demuxer.codecHint).isEqualTo(VideoCodec.H264)
            val types = nals.groupingBy { VideoCodec.H264.nalType(it.data[0]) }.eachCount()
            assertThat(types).containsExactly(9, 31, 7, 3, 8, 3, 5, 3, 1, 28)
            assertThat(nals.size).isEqualTo(68)
            assertThat(nals.first().timestamp90k).isEqualTo(126000L)
            val timestamps = nals.mapNotNull { it.timestamp90k }.distinct()
            assertThat(timestamps).hasSize(31)
            assertThat(timestamps[1] - timestamps[0]).isEqualTo(7500) // 12 fps at 90 kHz
            assertThat(demuxer.droppedPackets).isEqualTo(0)
        }

        @Test
        fun `whole file in one packet gives the same NALs`() {
            val demuxer = TsDemuxer()
            val nals = demuxer.feed(ts) + demuxer.flush()
            assertThat(nals).hasSize(68)
            // The SPS carries profile_idc 100 (High) as ffprobe reports.
            val sps = nals.first { VideoCodec.H264.nalType(it.data[0]) == 7 }
            assertThat(sps.data[1].toInt() and 0xFF).isEqualTo(100)
        }

        @Test
        fun `garbage before sync bytes is skipped and counted`() {
            val demuxer = TsDemuxer()
            val nals = demuxer.feed(byteArrayOf(1, 2, 3) + ts.copyOfRange(0, 188 * 400))
            assertThat(demuxer.droppedPackets).isAtLeast(1)
            assertThat(nals).isNotEmpty()
        }

        @Test
        fun `a lost packet mid picture drops that picture`() {
            val demuxer = TsDemuxer()
            // Remove one packet from the middle of the IDR (packets 10..11 are inside the first big PES: the
            // 13 KB IDR spans about 70 packets after the SDT, PAT and PMT).
            val cut = ts.copyOfRange(0, 188 * 10) + ts.copyOfRange(188 * 11, ts.size)
            val nals = demuxer.feed(cut) + demuxer.flush()
            assertThat(demuxer.droppedPackets).isEqualTo(1)
            assertThat(nals.size).isLessThan(68)
        }
    }

    @Nested
    inner class RtpPackets {
        private var seq = 100

        private fun rtp(
            payload: ByteArray,
            pt: Int = 96,
            ts: Long = 9000,
            ssrc: Int = 0x11223344,
            sequence: Int = seq++,
        ): ByteArray {
            val h = ByteArray(12)
            h[0] = 0x80.toByte()
            h[1] = pt.toByte()
            h[2] = (sequence ushr 8).toByte()
            h[3] = sequence.toByte()
            for (i in 0 until 4) h[4 + i] = (ts ushr (24 - 8 * i)).toByte()
            for (i in 0 until 4) h[8 + i] = (ssrc ushr (24 - 8 * i)).toByte()
            return h + payload
        }

        @Test
        fun `h264 single STAP-A and FU-A packets become NALs with the RTP timestamp`() {
            val demuxer = RtpDemuxer()
            val sps = byteArrayOf(0x67, 100, 0, 30, 1, 2, 3)
            val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
            val idr = byteArrayOf(0x65, 0x88.toByte()) + ByteArray(40) { it.toByte() }
            val stap = byteArrayOf(0x18) + size(sps) + sps + size(pps) + pps
            val fu1 = byteArrayOf(0x7C, 0x85.toByte()) + idr.copyOfRange(1, 20)
            val fu2 = byteArrayOf(0x7C, 0x05) + idr.copyOfRange(20, 30)
            val fu3 = byteArrayOf(0x7C, 0x45) + idr.copyOfRange(30, idr.size)
            val p = byteArrayOf(0x41, 0x9A.toByte(), 0x02)

            val out =
                listOf(rtp(stap), rtp(fu1, ts = 18000), rtp(fu2, ts = 18000), rtp(fu3, ts = 18000), rtp(p, ts = 27000))
                    .flatMap { demuxer.feed(it) }

            assertThat(demuxer.codecHint).isEqualTo(VideoCodec.H264)
            assertThat(
                out.map { it.data.toList() },
            ).containsExactly(sps.toList(), pps.toList(), idr.toList(), p.toList()).inOrder()
            assertThat(out.map { it.timestamp90k }).containsExactly(9000L, 9000L, 18000L, 27000L).inOrder()
            assertThat(demuxer.droppedPackets).isEqualTo(0)
        }

        @Test
        fun `codec is detected late from buffered packets`() {
            val demuxer = RtpDemuxer()
            val slice = byteArrayOf(0x41, 0x9A.toByte(), 0x02)

            assertThat(demuxer.feed(rtp(slice))).isEmpty() // ambiguous first byte: buffered
            assertThat(demuxer.codecHint).isNull()
            val out = demuxer.feed(rtp(byteArrayOf(0x67, 100, 0, 30)))

            assertThat(demuxer.codecHint).isEqualTo(VideoCodec.H264)
            assertThat(out).hasSize(2)
            assertThat(out[0].data).isEqualTo(slice)
        }

        @Test
        fun `after 400 undetectable packets HEVC is assumed and the buffer is released`() {
            val demuxer = RtpDemuxer()
            val opaque = byteArrayOf(0x02, 0x01, 0x77) // header-encrypted-looking payload, no markers
            var out = emptyList<NalUnit>()
            repeat(401) { out = demuxer.feed(rtp(opaque)) }
            assertThat(demuxer.codecHint).isEqualTo(VideoCodec.H265)
            assertThat(out).hasSize(401)
        }

        @Test
        fun `hevc FU and AP`() {
            val demuxer = RtpDemuxer()
            val vps = byteArrayOf(0x40, 0x01, 0x0C, 1, 2, 3)
            val sps = byteArrayOf(0x42, 0x01, 0x01, 1, 2, 3)
            val ap = byteArrayOf(0x60, 0x01) + size(vps) + vps + size(sps) + sps
            val idr = byteArrayOf(0x26, 0x01) + ByteArray(30) { (it + 1).toByte() }
            val fuStart = byteArrayOf(0x62, 0x01, (0x80 or 19).toByte()) + idr.copyOfRange(2, 12)
            val fuEnd = byteArrayOf(0x62, 0x01, (0x40 or 19).toByte()) + idr.copyOfRange(12, idr.size)

            val out = listOf(rtp(ap), rtp(fuStart), rtp(fuEnd)).flatMap { demuxer.feed(it) }

            assertThat(demuxer.codecHint).isEqualTo(VideoCodec.H265)
            assertThat(out.map { it.data.toList() }).containsExactly(vps.toList(), sps.toList(), idr.toList()).inOrder()
        }

        @Test
        fun `a lost fragment drops the NAL instead of gluing it`() {
            val demuxer = RtpDemuxer(VideoCodec.H264)
            val fu1 = byteArrayOf(0x7C, 0x85.toByte()) + ByteArray(10) { 1 }
            val fu3 = byteArrayOf(0x7C, 0x45) + ByteArray(10) { 3 }
            val next = byteArrayOf(0x41, 0x9A.toByte())

            demuxer.feed(rtp(fu1, sequence = 1))
            val lost = demuxer.feed(rtp(fu3, sequence = 3))
            val after = demuxer.feed(rtp(next, sequence = 4))

            assertThat(lost).isEmpty()
            assertThat(after).hasSize(1)
            assertThat(demuxer.droppedPackets).isEqualTo(1)
        }

        @Test
        fun `duplicates and late packets are dropped, other payload types ignored, sequence reset accepted`() {
            val demuxer = RtpDemuxer(VideoCodec.H264)
            val nal = byteArrayOf(0x41, 0x9A.toByte())

            assertThat(demuxer.feed(rtp(nal, sequence = 10))).hasSize(1)
            assertThat(demuxer.feed(rtp(nal, sequence = 10))).isEmpty() // duplicate
            assertThat(demuxer.feed(rtp(nal, sequence = 9))).isEmpty() // late
            assertThat(demuxer.feed(rtp(nal, pt = 112, sequence = 1))).isEmpty() // metadata stream, own counter
            assertThat(demuxer.feed(rtp(nal, pt = 0, sequence = 1))).isEmpty() // audio
            assertThat(demuxer.feed(rtp(nal, sequence = 11))).hasSize(1)
            assertThat(demuxer.droppedPackets).isEqualTo(2)
            // A stream that restarted its sequence: after 50 "late" packets we follow it.
            repeat(50) { demuxer.feed(rtp(nal, sequence = 1000 + it)) }
            var accepted = 0
            repeat(50) { if (demuxer.feed(rtp(nal, sequence = 5 + it)).isNotEmpty()) accepted++ }
            assertThat(accepted).isEqualTo(1)
        }

        @Test
        fun `payload strips CSRC extension and padding and rejects malformed packets`() {
            val base = rtp(byteArrayOf(1, 2, 3))
            assertThat(RtpDemuxer.payload(base)).isEqualTo(byteArrayOf(1, 2, 3))
            val withCsrc = base.copyOf().also { it[0] = 0x81.toByte() } // 1 CSRC = payload shifts 4 bytes
            assertThat(RtpDemuxer.payload(withCsrc + byteArrayOf(9, 8))).isEqualTo(byteArrayOf(8))
            val ext =
                rtp(byteArrayOf(0, 0, 0, 1, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 7))
                    .also { it[0] = 0x90.toByte() }
            assertThat(RtpDemuxer.payload(ext)).isEqualTo(byteArrayOf(7))
            val padded = rtp(byteArrayOf(5, 0, 0, 3)).also { it[0] = 0xA0.toByte() }
            assertThat(RtpDemuxer.payload(padded)).isEqualTo(byteArrayOf(5))

            assertThrows<RtpException> { RtpDemuxer.payload(ByteArray(5)) }
            assertThrows<RtpException> { RtpDemuxer.payload(ByteArray(12)) } // version 0
            assertThrows<RtpException> { RtpDemuxer.payload(ByteArray(12).also { it[0] = 0x8F.toByte() }) }
            assertThrows<RtpException> { RtpDemuxer.payload(ByteArray(12).also { it[0] = 0x90.toByte() }) }
            assertThrows<RtpException> { RtpDemuxer.payload(ByteArray(12).also { it[0] = 0xA0.toByte() }) }
            assertThrows<RtpException> { RtpDemuxer.payload(rtp(byteArrayOf(0)).also { it[0] = 0xA0.toByte() }) }
            val short = ByteArray(5)
            assertThat(RtpDemuxer(VideoCodec.H264).feed(short)).isEmpty()
        }

        @Test
        fun `detectCodec only trusts unambiguous markers`() {
            assertThat(RtpDemuxer.detectCodec(0x67)).isEqualTo(VideoCodec.H264)
            assertThat(RtpDemuxer.detectCodec(0x7C)).isEqualTo(VideoCodec.H264)
            assertThat(RtpDemuxer.detectCodec(0x40)).isEqualTo(VideoCodec.H265)
            assertThat(RtpDemuxer.detectCodec(0x62)).isEqualTo(VideoCodec.H265)
            assertThat(RtpDemuxer.detectCodec(0x41)).isNull()
        }

        private fun size(b: ByteArray) = byteArrayOf((b.size ushr 8).toByte(), b.size.toByte())
    }

    @Nested
    inner class ProgramStream {
        private fun pes(
            nalsAnnexB: ByteArray,
            pts: Long? = null,
            unbounded: Boolean = false,
        ): ByteArray {
            val header =
                if (pts != null) {
                    byteArrayOf(0x80.toByte(), 0x80.toByte(), 5) + ptsBytes(pts)
                } else {
                    byteArrayOf(0x80.toByte(), 0x00, 0)
                }
            val length = if (unbounded) 0 else header.size + nalsAnnexB.size
            return byteArrayOf(0, 0, 1, 0xE0.toByte(), (length ushr 8).toByte(), length.toByte()) + header + nalsAnnexB
        }

        private fun pack() = byteArrayOf(0, 0, 1, 0xBA.toByte(), 0x44) + ByteArray(8) + byteArrayOf(0xF8.toByte())

        private fun ptsBytes(pts: Long): ByteArray = byteArrayOf(
            (0x21 or (((pts ushr 30) and 0x7).toInt() shl 1)).toByte(),
            ((pts ushr 22) and 0xFF).toByte(),
            (((pts ushr 15) and 0x7F).toInt() shl 1 or 1).toByte(),
            ((pts ushr 7) and 0xFF).toByte(),
            (((pts and 0x7F).toInt() shl 1) or 1).toByte(),
        )

        private val sps = byteArrayOf(0x67, 100, 0, 30)
        private val idr = byteArrayOf(0x65, 0x88.toByte(), 1, 2, 3)
        private val p = byteArrayOf(0x41, 0x9A.toByte(), 7)
        private val start = byteArrayOf(0, 0, 0, 1)

        @Test
        fun `bounded PES with PTS and audio PES in between`() {
            val demuxer = AutoDemuxer()
            val audio = byteArrayOf(0, 0, 1, 0xC0.toByte(), 0, 3, 1, 2, 3)
            val stream =
                pack() + pes(start + sps + start + idr, pts = 90_000) + audio + pack() + pes(start + p, pts = 97_500)

            val out = demuxer.feed(stream) + demuxer.feed(pes(start + p, pts = 105_000)) + demuxer.feed(pack())

            assertThat(demuxer.container).isEqualTo(AutoDemuxer.Container.MPEG_PS)
            assertThat(out.map { it.data.toList() })
                .containsExactly(sps.toList(), idr.toList(), p.toList(), p.toList())
                .inOrder()
            assertThat(out.map { it.timestamp90k }).containsExactly(90_000L, 90_000L, 97_500L, 105_000L).inOrder()
        }

        @Test
        fun `unbounded PES ends at the next PS start code not at NAL start codes`() {
            val demuxer = PsDemuxer()
            val stream =
                pack() + pes(start + sps + start + idr + start + p, pts = 1, unbounded = true) + pack() +
                    pes(start + p, pts = 2, unbounded = true)

            val out = demuxer.feed(stream) + demuxer.feed(byteArrayOf(0, 0, 1, 0xB9.toByte()))

            assertThat(
                out.map { it.data.toList() },
            ).containsExactly(sps.toList(), idr.toList(), p.toList(), p.toList()).inOrder()
            assertThat(out.map { it.timestamp90k }).containsExactly(1L, 1L, 1L, 2L).inOrder()
        }

        @Test
        fun `split across arbitrary chunk boundaries`() {
            val whole = pack() + pes(start + sps + start + idr, pts = 5) + pack() + pes(start + p, pts = 6) + pack()
            for (chunk in listOf(1, 3, 7, 16)) {
                val demuxer = PsDemuxer()
                val out = whole.toList().chunked(chunk).flatMap { demuxer.feed(it.toByteArray()) }
                assertThat(
                    out.map { it.data.toList() },
                ).containsExactly(sps.toList(), idr.toList(), p.toList()).inOrder()
            }
        }

        @Test
        fun `garbage without start codes is capped and resynchronised`() {
            val demuxer = PsDemuxer()
            demuxer.feed(ByteArray(5 shl 20) { 0x55 })
            assertThat(demuxer.resyncs).isEqualTo(1)
            val out = demuxer.feed(pack() + pes(start + sps + start + idr, pts = 1) + pack())
            assertThat(out).hasSize(2)
        }
    }

    @Test
    fun `AutoDemuxer picks RTP for version-2 packets`() {
        val demuxer = AutoDemuxer()
        assertThat(demuxer.container).isNull()
        demuxer.feed(byteArrayOf(0x80.toByte(), 96, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0x67, 100))
        assertThat(demuxer.container).isEqualTo(AutoDemuxer.Container.RTP)
        assertThat(demuxer.codecHint).isEqualTo(VideoCodec.H264)
        assertThat(demuxer.droppedPackets).isEqualTo(0)
    }
}
