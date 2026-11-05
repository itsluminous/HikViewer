package com.itsluminous.hikviewer.core.stream

import com.google.common.truth.Truth.assertThat
import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer

/**
 * Hot-path cost of [DecodePipeline] on the JVM: one encrypted GOP of the real sub-stream, packed
 * into RTP (FU-A for slices over the MTU, single-NAL otherwise) and replayed many times. Reports
 * microseconds and heap bytes allocated per picture so a change to the demux/decrypt/assemble
 * path can be judged by numbers; the assertions are loose (the point is the printed table, which
 * `.research/CPU.md` records), but they keep the pipeline from regressing into per-byte copies.
 */
class DecodePipelineBenchmarkTest {
    private val nals = NalDecryptorTest.readUnits("h264_nals_header_clear_all.bin")
    private val gop = rtpPackets(nals)

    @Test
    fun `cost per picture stays bounded`() {
        val warm = replay(200)
        val measured = replay(GOPS)
        val pictures = measured.pictures
        val perPictureUs = measured.nanos / 1_000.0 / pictures
        val perPictureBytes = measured.allocated.toDouble() / pictures
        val payloadPerPicture = gop.sumOf { it.size }.toDouble() / warm.picturesPerGop
        println(
            "pipeline: %d pictures, %.1f us/picture, %.0f B allocated/picture (%.1fx the %.0f B payload)".format(
                pictures,
                perPictureUs,
                perPictureBytes,
                perPictureBytes / payloadPerPicture,
                payloadPerPicture,
            ),
        )
        // The last picture of the final GOP is still in the assembler.
        assertThat(pictures).isAtLeast(GOPS * warm.picturesPerGop - 1)
        // Every picture is copied into the access unit at least once; more than a handful of
        // copies of the payload per picture means a per-packet or per-NAL copy crept back in.
        assertThat(perPictureBytes / payloadPerPicture).isLessThan(MAX_ALLOCATION_RATIO)
    }

    private class Result(
        val pictures: Int,
        val nanos: Long,
        val allocated: Long,
        val picturesPerGop: Int,
    )

    private fun replay(gops: Int): Result {
        val pipeline = DecodePipeline(NalDecryptorTest.KEY)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val tid = Thread.currentThread().id
        var pictures = 0
        var firstGop = 0
        val allocatedBefore = bean.getThreadAllocatedBytes(tid)
        val start = System.nanoTime()
        var seq = 0
        var ts = 0L
        repeat(gops) { g ->
            for (packet in gop) {
                // Sequence numbers and timestamps must keep running across replays (a restart is
                // treated as late packets); patched in place so the replay itself allocates nothing.
                packet[2] = (seq ushr 8).toByte()
                packet[3] = seq.toByte()
                seq = (seq + 1) and 0xFFFF
                if (packet[12].toInt() and 0x1F in 1..5 || isFragmentStart(packet)) ts += TICKS_PER_FRAME
                for (i in 0 until 4) packet[4 + i] = (ts ushr (24 - 8 * i)).toByte()
                val out = pipeline.process(packet)
                pictures += out.size
                if (g == 0) firstGop += out.size
                for (unit in out) check(unit.data.isNotEmpty())
            }
        }
        val nanos = System.nanoTime() - start
        val allocated = bean.getThreadAllocatedBytes(tid) - allocatedBefore
        // The first GOP primes variant detection (its pictures come out one GOP late).
        return Result(pictures, nanos, allocated, picturesPerGop = (pictures - firstGop) / (gops - 1).coerceAtLeast(1))
    }

    /** True for the first fragment of a FU-A: a new slice (picture) begins here. */
    private fun isFragmentStart(packet: ByteArray): Boolean =
        packet[12].toInt() and 0x1F == 28 && packet[13].toInt() and 0x80 != 0

    /** RTP-packs [units]: FU-A fragments for NALs above [MTU], single-NAL packets otherwise. */
    private fun rtpPackets(units: List<ByteArray>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for (nal in units) {
            val type = nal[0].toInt() and 0x1F
            if (nal.size <= MTU) {
                out += rtp(nal)
                continue
            }
            val nri = nal[0].toInt() and 0x60
            var offset = 1
            var first = true
            while (offset < nal.size) {
                val end = minOf(offset + MTU - 2, nal.size)
                val last = end == nal.size
                val fu = (if (first) 0x80 else 0) or (if (last) 0x40 else 0) or type
                val payload = byteArrayOf((nri or 28).toByte(), fu.toByte()) + nal.copyOfRange(offset, end)
                out += rtp(payload)
                offset = end
                first = false
            }
        }
        return out
    }

    /** RTP header with sequence and timestamp left zero; [replay] fills them in. */
    private fun rtp(payload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(12 + payload.size)
        b
            .put(0x80.toByte())
            .put(96.toByte())
            .putShort(0)
            .putInt(0)
            .putInt(0x11223344)
        b.put(payload)
        return b.array()
    }

    private companion object {
        const val GOPS = 3_000
        const val MTU = 1_400
        const val MAX_ALLOCATION_RATIO = 8.0
        const val TICKS_PER_FRAME = 7_500L // 90 kHz at 12 fps
    }
}
