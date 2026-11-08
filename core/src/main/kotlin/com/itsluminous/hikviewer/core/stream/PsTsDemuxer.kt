package com.itsluminous.hikviewer.core.stream

import java.io.ByteArrayOutputStream

/** PES header parsing shared by the PS and TS demuxers. */
internal object Pes {
    const val HEADER_FIXED = 9 // 00 00 01 | stream id | length(2) | flags | flags | header data length
    private const val PTS_FLAG = 0x80
    private const val BYTE_MASK = 0xFF

    fun isVideoStreamId(id: Int): Boolean = id in VIDEO_STREAM_IDS

    /** Offset of the payload inside a PES starting at [pos], or -1 when the header is incomplete. */
    fun payloadStart(
        b: ByteArray,
        pos: Int,
    ): Int {
        if (pos + HEADER_FIXED > b.size) return -1
        val start = pos + HEADER_FIXED + (b[pos + 8].toInt() and BYTE_MASK)
        return if (start > b.size) -1 else start
    }

    /** 33-bit PTS of the PES at [pos], or null when absent. */
    fun pts(
        b: ByteArray,
        pos: Int,
    ): Long? {
        if (pos + HEADER_FIXED + 5 > b.size || b[pos + 7].toInt() and PTS_FLAG == 0) return null
        // PTS is 33 bits spread over 5 bytes: 0010 xxx1 | xxxxxxxx | xxxxxxx1 | xxxxxxxx | xxxxxxx1
        val p = pos + HEADER_FIXED
        var pts = (b[p].toLong() and PTS_TOP_MASK) shl PTS_SHIFT_TOP
        pts = pts or ((b[p + 1].toLong() and BYTE_MASK.toLong()) shl PTS_SHIFT_HIGH)
        pts = pts or ((b[p + 2].toLong() and PTS_MARKER_MASK) shl PTS_SHIFT_MID)
        pts = pts or ((b[p + 3].toLong() and BYTE_MASK.toLong()) shl PTS_SHIFT_LOW)
        pts = pts or ((b[p + 4].toLong() and PTS_MARKER_MASK) ushr 1)
        return pts
    }

    private val VIDEO_STREAM_IDS = 0xE0..0xEF
    private const val PTS_TOP_MASK = 0x0EL
    private const val PTS_MARKER_MASK = 0xFEL
    private const val PTS_SHIFT_TOP = 29
    private const val PTS_SHIFT_HIGH = 22
    private const val PTS_SHIFT_MID = 14
    private const val PTS_SHIFT_LOW = 7
}

/**
 * MPEG program stream demuxer: extracts video PES payloads (stream ids 0xE0 to 0xEF) into NAL units.
 *
 * A PES ends at its length, or for an unbounded PES (length 0) at the next PS-level start code
 * (pack header, system header, another PES), never at a NAL start code inside it; both are
 * `00 00 01` so the fourth byte decides. A PES boundary is **not** a NAL boundary: the PES length
 * field is 16 bits, so the recorder splits a NAL bigger than about 64 KB (every 1080p HEVC IDR,
 * 100 KB and more) over several PES packets, only the first of which carries the PTS. The NAL held
 * back by the splitter is therefore released when a PES with a *new* PTS starts the next picture,
 * at a program end code and on [flush]; releasing it at every PES end cut the tail off every
 * keyframe (the bottom of the picture) in recorded playback. Buffers are capped: on
 * start-code-free garbage the demuxer resynchronises at the last start code instead of growing
 * forever.
 */
public class PsDemuxer : Demuxer {
    override val codecHint: VideoCodec? = null
    override var droppedPackets: Int = 0
        private set

    /** Times a buffer overflowed and was resynchronised. */
    public var resyncs: Int = 0
        private set

    private val pending = ByteArrayOutputStream()
    private val splitter = AnnexBSplitter()

    override val discardedBytes: Int get() = splitter.discardedBytes

    override fun feed(packet: ByteArray): List<NalUnit> {
        pending.write(packet)
        val out = demux()
        cap()
        return out
    }

    private fun demux(): List<NalUnit> {
        val b = pending.toByteArray()
        val n = b.size
        val out = ArrayList<NalUnit>()
        var pos = 0
        while (true) {
            val sc = indexOfStartCode(b, pos)
            if (sc < 0 || sc + 4 > n) break
            val code = b[sc + 3].toInt() and BYTE_MASK
            val next =
                when {
                    code == PACK_HEADER -> packHeaderEnd(b, sc)
                    code == PROGRAM_END -> {
                        out += splitter.flush()
                        sc + 4
                    }
                    code < PROGRAM_END -> sc + 3 // not a PS start code (NAL start code or resync leftovers)
                    Pes.isVideoStreamId(code) -> videoPes(b, sc, out)
                    else -> skipOtherPes(b, sc)
                }
            if (next < 0) break
            pos = next
        }
        pending.reset()
        pending.write(b, pos, n - pos)
        return out
    }

    private fun packHeaderEnd(
        b: ByteArray,
        sc: Int,
    ): Int {
        if (sc + PACK_HEADER_LENGTH > b.size) return -1
        val end = sc + PACK_HEADER_LENGTH + (b[sc + PACK_HEADER_LENGTH - 1].toInt() and STUFFING_MASK)
        return if (end > b.size) -1 else end
    }

    private fun videoPes(
        b: ByteArray,
        sc: Int,
        out: MutableList<NalUnit>,
    ): Int {
        val payloadStart = Pes.payloadStart(b, sc)
        if (payloadStart < 0) return -1
        val length = ((b[sc + 4].toInt() and BYTE_MASK) shl 8) or (b[sc + 5].toInt() and BYTE_MASK)
        val end = if (length > 0) sc + 6 + length else nextPsStartCode(b, payloadStart)
        if (end < 0 || end > b.size) return -1
        // A PTS marks the first PES of a picture: the previous picture is complete, so the NAL the
        // splitter holds back belongs to it and is released with the old timestamp. A PES without a
        // PTS (or with the same one) continues the picture, possibly mid-NAL, and is only appended.
        val pts = Pes.pts(b, sc)
        if (pts != null && pts != splitter.timestamp) {
            out += splitter.flush()
            splitter.timestamp = pts
        }
        out += splitter.append(b, payloadStart, end)
        return end
    }

    override fun flush(): List<NalUnit> = splitter.flush()

    private fun skipOtherPes(
        b: ByteArray,
        sc: Int,
    ): Int {
        if (sc + 6 > b.size) return -1
        val length = ((b[sc + 4].toInt() and BYTE_MASK) shl 8) or (b[sc + 5].toInt() and BYTE_MASK)
        val end = sc + 6 + length
        return if (end > b.size) -1 else end
    }

    /** Next `00 00 01 xx` with xx >= 0xB9 (a PS-level code), skipping NAL start codes. */
    private fun nextPsStartCode(
        b: ByteArray,
        from: Int,
    ): Int {
        var i = indexOfStartCode(b, from)
        while (i >= 0) {
            if (i + 3 >= b.size) return -1
            if ((b[i + 3].toInt() and BYTE_MASK) >= PROGRAM_END) return i
            i = indexOfStartCode(b, i + 3)
        }
        return -1
    }

    private fun cap() {
        if (pending.size() > MAX_BUFFER) {
            val b = pending.toByteArray()
            val last = lastStartCode(b)
            pending.reset()
            if (last > 0) pending.write(b, last, b.size - last)
            resyncs++
        }
        if (splitter.pendingBytes > MAX_BUFFER) {
            splitter.reset()
            resyncs++
        }
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val PACK_HEADER = 0xBA
        const val PROGRAM_END = 0xB9
        const val PACK_HEADER_LENGTH = 14
        const val STUFFING_MASK = 0x07
        const val MAX_BUFFER = 4 shl 20

        fun indexOfStartCode(
            b: ByteArray,
            from: Int,
        ): Int {
            var i = maxOf(from, 0)
            while (i + 2 < b.size) {
                if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) return i
                i++
            }
            return -1
        }

        fun lastStartCode(b: ByteArray): Int {
            var i = b.size - 3
            while (i >= 0) {
                if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) return i
                i--
            }
            return -1
        }
    }
}

/**
 * MPEG transport stream demuxer. Follows PAT then PMT to find the video PID (stream types 0x1B
 * H.264, 0x24 HEVC); without tables it locks onto the first PID whose unit start carries a video
 * PES. As in [PsDemuxer], a PES boundary is not a NAL boundary: the held-back NAL is released when
 * a PES with a new PTS starts the next picture and on [flush], so a bounded PES chain carrying one
 * large NAL is reassembled whole.
 */
public class TsDemuxer : Demuxer {
    override var codecHint: VideoCodec? = null
        private set
    override var droppedPackets: Int = 0
        private set

    private var pmtPid = -1
    private var videoPid = -1
    private val pes = ByteArrayOutputStream()
    private val splitter = AnnexBSplitter()
    private var continuity = -1

    override fun feed(packet: ByteArray): List<NalUnit> {
        val out = ArrayList<NalUnit>()
        var offset = 0
        while (offset + PACKET_SIZE <= packet.size) {
            if (packet[offset] != SYNC_BYTE) {
                droppedPackets++
                offset = resync(packet, offset)
                continue
            }
            tsPacket(packet, offset, out)
            offset += PACKET_SIZE
        }
        return out
    }

    private fun resync(
        packet: ByteArray,
        from: Int,
    ): Int {
        var i = from + 1
        while (i < packet.size && packet[i] != SYNC_BYTE) i++
        return i
    }

    private fun tsPacket(
        p: ByteArray,
        at: Int,
        out: MutableList<NalUnit>,
    ) {
        val pid = ((p[at + 1].toInt() and PID_HIGH_MASK) shl 8) or (p[at + 2].toInt() and BYTE_MASK)
        val unitStart = p[at + 1].toInt() and UNIT_START_FLAG != 0
        val flags = p[at + 3].toInt() and BYTE_MASK
        val hasAdaptation = flags and ADAPTATION_FLAG != 0
        val hasPayload = flags and PAYLOAD_FLAG != 0
        if (!hasPayload) return
        var payload = at + 4
        if (hasAdaptation) payload += 1 + (p[at + 4].toInt() and BYTE_MASK)
        if (payload > at + PACKET_SIZE) {
            droppedPackets++
            return
        }
        val end = at + PACKET_SIZE
        when {
            pid == PAT_PID -> parsePat(p, payload, end, unitStart)
            pid == pmtPid -> parsePmt(p, payload, end, unitStart)
            pid == videoPid -> videoPayload(p, payload until end, unitStart, flags and CONTINUITY_MASK, out)
            videoPid < 0 && pmtPid < 0 && unitStart && looksLikeVideoPes(p, payload, end) -> {
                videoPid = pid
                videoPayload(p, payload until end, unitStart, flags and CONTINUITY_MASK, out)
            }
        }
    }

    private fun looksLikeVideoPes(
        p: ByteArray,
        from: Int,
        end: Int,
    ): Boolean = end - from >= 4 &&
        p[from] == 0.toByte() &&
        p[from + 1] == 0.toByte() &&
        p[from + 2] == 1.toByte() &&
        Pes.isVideoStreamId(p[from + 3].toInt() and BYTE_MASK)

    private fun parsePat(
        p: ByteArray,
        from: Int,
        end: Int,
        unitStart: Boolean,
    ) {
        val section = sectionStart(p, from, end, unitStart) ?: return
        // table_id(1) section_length(2) tsid(2) version(1) section#(1) last#(1) then 4-byte entries, 4-byte CRC
        var i = section + PAT_HEADER_LENGTH
        while (i + 4 <= end - CRC_LENGTH) {
            val program = ((p[i].toInt() and BYTE_MASK) shl 8) or (p[i + 1].toInt() and BYTE_MASK)
            val pid = ((p[i + 2].toInt() and PID_HIGH_MASK) shl 8) or (p[i + 3].toInt() and BYTE_MASK)
            if (program != 0) {
                pmtPid = pid
                return
            }
            i += 4
        }
    }

    private fun parsePmt(
        p: ByteArray,
        from: Int,
        end: Int,
        unitStart: Boolean,
    ) {
        val section = sectionStart(p, from, end, unitStart) ?: return
        if (section + PMT_HEADER_LENGTH > end) return
        val sectionLength = length12(p, section + 1)
        val sectionEnd = minOf(section + 3 + sectionLength - CRC_LENGTH, end)
        val infoLength = length12(p, section + PMT_INFO_LENGTH_OFFSET)
        var i = section + PMT_HEADER_LENGTH + infoLength
        while (i + ES_ENTRY_LENGTH <= sectionEnd) {
            val streamType = p[i].toInt() and BYTE_MASK
            val pid = ((p[i + 1].toInt() and PID_HIGH_MASK) shl 8) or (p[i + 2].toInt() and BYTE_MASK)
            val esInfo = length12(p, i + 3)
            val codec =
                when (streamType) {
                    STREAM_TYPE_H264 -> VideoCodec.H264
                    STREAM_TYPE_HEVC -> VideoCodec.H265
                    else -> null
                }
            if (codec != null && videoPid < 0) {
                videoPid = pid
                codecHint = codec
            }
            i += ES_ENTRY_LENGTH + esInfo
        }
    }

    /** 12-bit length field (low nibble of the first byte + second byte). */
    private fun length12(
        p: ByteArray,
        at: Int,
    ): Int = ((p[at].toInt() and SECTION_LENGTH_HIGH_MASK) shl 8) or (p[at + 1].toInt() and BYTE_MASK)

    private fun sectionStart(
        p: ByteArray,
        from: Int,
        end: Int,
        unitStart: Boolean,
    ): Int? {
        if (!unitStart || from >= end) return null
        val pointer = p[from].toInt() and BYTE_MASK
        val start = from + 1 + pointer
        return if (start >= end) null else start
    }

    private fun videoPayload(
        p: ByteArray,
        range: IntRange,
        unitStart: Boolean,
        cc: Int,
        out: MutableList<NalUnit>,
    ) {
        if (continuity >= 0 && cc != (continuity + 1) and CONTINUITY_MASK && !unitStart) {
            // Lost a packet mid-PES: the current picture is unusable, drop it.
            droppedPackets++
            pes.reset()
            splitter.reset()
        }
        continuity = cc
        if (unitStart) {
            out += completePes()
        }
        pes.write(p, range.first, range.last - range.first + 1)
        if (pes.size() > MAX_PES) {
            droppedPackets++
            pes.reset()
        }
    }

    override fun flush(): List<NalUnit> = completePes() + splitter.flush()

    override val discardedBytes: Int get() = splitter.discardedBytes

    /** Parses the accumulated PES (header + ES) and returns all its NALs. */
    private fun completePes(): List<NalUnit> {
        val b = pes.toByteArray()
        pes.reset()
        if (b.size < Pes.HEADER_FIXED) return emptyList()
        val payloadStart = Pes.payloadStart(b, 0)
        if (payloadStart < 0) return emptyList()
        val out = ArrayList<NalUnit>()
        val pts = Pes.pts(b, 0)
        if (pts != null && pts != splitter.timestamp) {
            out += splitter.flush()
            splitter.timestamp = pts
        }
        out += splitter.append(b, payloadStart, b.size)
        return out
    }

    /** Transport stream constants. */
    public companion object {
        /** TS packet length. */
        public const val PACKET_SIZE: Int = 188

        /** First byte of every TS packet. */
        public const val SYNC_BYTE: Byte = 0x47
        private const val BYTE_MASK = 0xFF
        private const val PID_HIGH_MASK = 0x1F
        private const val UNIT_START_FLAG = 0x40
        private const val ADAPTATION_FLAG = 0x20
        private const val PAYLOAD_FLAG = 0x10
        private const val CONTINUITY_MASK = 0x0F
        private const val PAT_PID = 0
        private const val PAT_HEADER_LENGTH = 8
        private const val PMT_HEADER_LENGTH = 12
        private const val PMT_INFO_LENGTH_OFFSET = 10
        private const val ES_ENTRY_LENGTH = 5
        private const val CRC_LENGTH = 4
        private const val SECTION_LENGTH_HIGH_MASK = 0x0F
        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_HEVC = 0x24
        private const val MAX_PES = 4 shl 20
    }
}
