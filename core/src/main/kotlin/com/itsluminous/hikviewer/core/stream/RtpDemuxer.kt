package com.itsluminous.hikviewer.core.stream

import java.io.ByteArrayOutputStream

/**
 * RTP depacketiser for H.264 (RFC 6184: single NAL, STAP-A, FU-A) and HEVC (RFC 7798: single, AP,
 * FU). Only payload type 96 (video) is used; Hik streams carry metadata (112) and audio (0) in the
 * same SSRC.
 *
 * Sequence numbers are tracked per (SSRC, payload type) because each payload type has its own
 * counter; a gap in the video sequence drops the fragmented NAL being assembled rather than gluing
 * the halves (with AES-ECB a missing fragment would shift every following 16-byte block and turn the
 * rest of the NAL into noise). Until the codec is known, packets are buffered (at most
 * [MAX_UNDECIDED_PACKETS], then HEVC is assumed) because FU/aggregation parsing depends on it.
 */
public class RtpDemuxer(
    codec: VideoCodec? = null,
) : Demuxer {
    override var codecHint: VideoCodec? = codec
        private set
    override var droppedPackets: Int = 0
        private set

    private val undecided = ArrayList<ByteArray>()
    private val lastSequence = HashMap<Long, Int>()
    private val lateRun = HashMap<Long, Int>()
    private var fragment: ByteArrayOutputStream? = null
    private var fragmentTimestamp: Long? = null

    override fun feed(packet: ByteArray): List<NalUnit> {
        if (packet.size < HEADER_SIZE) {
            droppedPackets++
            return emptyList()
        }
        if (!acceptSequence(packet)) return emptyList()
        if (payloadType(packet) != VIDEO_PAYLOAD_TYPE) return emptyList()
        val codec = codecHint ?: return bufferUntilCodecKnown(packet)
        return depacketize(packet, codec)
    }

    private fun bufferUntilCodecKnown(packet: ByteArray): List<NalUnit> {
        undecided += packet
        val payload = runCatching { payload(packet) }.getOrNull()
        val detected = payload?.takeIf { it.isNotEmpty() }?.let { detectCodec(it[0]) }
        if (detected == null && undecided.size <= MAX_UNDECIDED_PACKETS) return emptyList()
        // No unambiguous marker in hundreds of packets: the headers are probably encrypted too. Fall
        // back to HEVC as the reference client does; single-NAL packets pass through unchanged either way.
        val codec = detected ?: VideoCodec.H265
        codecHint = codec
        val out = ArrayList<NalUnit>()
        undecided.forEach { out += depacketize(it, codec) }
        undecided.clear()
        return out
    }

    private fun depacketize(
        packet: ByteArray,
        codec: VideoCodec,
    ): List<NalUnit> {
        val payload = runCatching { payload(packet) }.getOrNull()
        if (payload == null) {
            droppedPackets++
            fragment = null
            return emptyList()
        }
        val timestamp = timestamp(packet)
        if (payload.size < codec.nalHeaderLength) return emptyList()
        val type = codec.nalType(payload[0])
        return when {
            type == fuType(codec) -> fragmentUnit(payload, codec, timestamp)
            type == aggregationType(codec) -> aggregation(payload, codec, timestamp)
            else -> listOf(NalUnit(payload, timestamp))
        }
    }

    private fun fragmentUnit(
        payload: ByteArray,
        codec: VideoCodec,
        timestamp: Long,
    ): List<NalUnit> {
        val hdr = codec.nalHeaderLength
        if (payload.size < hdr + 1) return emptyList()
        val fu = payload[hdr].toInt() and BYTE_MASK
        val start = fu and FU_START != 0
        val end = fu and FU_END != 0
        if (start) {
            val reconstructed = ByteArrayOutputStream()
            if (codec == VideoCodec.H264) {
                reconstructed.write((payload[0].toInt() and H264_NRI_MASK) or (fu and H264_TYPE_MASK))
            } else {
                reconstructed.write((payload[0].toInt() and HEVC_HEADER_KEEP_MASK) or ((fu and HEVC_TYPE_MASK) shl 1))
                reconstructed.write(payload[1].toInt())
            }
            reconstructed.write(payload, hdr + 1, payload.size - hdr - 1)
            fragment = reconstructed
            fragmentTimestamp = timestamp
        } else {
            fragment?.write(payload, hdr + 1, payload.size - hdr - 1)
        }
        val current = fragment
        if (end && current != null) {
            fragment = null
            return listOf(NalUnit(current.toByteArray(), fragmentTimestamp))
        }
        return emptyList()
    }

    private fun aggregation(
        payload: ByteArray,
        codec: VideoCodec,
        timestamp: Long,
    ): List<NalUnit> {
        val out = ArrayList<NalUnit>()
        var i = codec.nalHeaderLength
        while (i + 2 <= payload.size) {
            val size = ((payload[i].toInt() and BYTE_MASK) shl 8) or (payload[i + 1].toInt() and BYTE_MASK)
            i += 2
            val end = minOf(i + size, payload.size)
            if (end > i) out += NalUnit(payload.copyOfRange(i, end), timestamp)
            i = end
        }
        return out
    }

    private fun acceptSequence(packet: ByteArray): Boolean {
        val seq = ((packet[2].toInt() and BYTE_MASK) shl 8) or (packet[3].toInt() and BYTE_MASK)
        val isVideo = payloadType(packet) == VIDEO_PAYLOAD_TYPE
        val stream = streamKey(packet)
        val last = lastSequence[stream]
        if (last == null) {
            lastSequence[stream] = seq
            return true
        }
        val diff = (seq - last) and SEQUENCE_MASK
        if (diff == 0) {
            droppedPackets++
            return false
        }
        if (diff >= SEQUENCE_HALF) {
            // A late (old) packet: do not move the counter back. Many in a row means the sender
            // restarted its sequence, so accept from there.
            val run = (lateRun[stream] ?: 0) + 1
            if (run < LATE_RUN_RESET) {
                lateRun[stream] = run
                droppedPackets++
                return false
            }
        }
        lateRun.remove(stream)
        lastSequence[stream] = seq
        if (diff != 1 && isVideo) {
            droppedPackets++
            fragment = null
        }
        return true
    }

    /** (SSRC, payload type) packed in a long. */
    private fun streamKey(packet: ByteArray): Long {
        var ssrc = 0L
        for (i in SSRC_OFFSET until SSRC_OFFSET + 4) ssrc = (ssrc shl 8) or (packet[i].toLong() and BYTE_MASK.toLong())
        return (ssrc shl 8) or payloadType(packet).toLong()
    }

    private fun payloadType(packet: ByteArray): Int = packet[1].toInt() and PAYLOAD_TYPE_MASK

    private fun timestamp(packet: ByteArray): Long {
        var ts = 0L
        for (i in TIMESTAMP_OFFSET until TIMESTAMP_OFFSET + 4) {
            ts =
                (ts shl 8) or (packet[i].toLong() and BYTE_MASK.toLong())
        }
        return ts
    }

    private fun fuType(codec: VideoCodec) = if (codec == VideoCodec.H264) H264_FU_A else HEVC_FU

    private fun aggregationType(codec: VideoCodec) = if (codec == VideoCodec.H264) H264_STAP_A else HEVC_AP

    /** Constants of the RTP header and the two payload formats. */
    public companion object {
        /** Fixed RTP header length. */
        public const val HEADER_SIZE: Int = 12

        /** Payload type of the video stream in Hik relays. */
        public const val VIDEO_PAYLOAD_TYPE: Int = 96
        private const val MAX_UNDECIDED_PACKETS = 400
        private const val BYTE_MASK = 0xFF
        private const val PAYLOAD_TYPE_MASK = 0x7F
        private const val SEQUENCE_MASK = 0xFFFF
        private const val SEQUENCE_HALF = 0x8000
        private const val LATE_RUN_RESET = 50
        private const val SSRC_OFFSET = 8
        private const val TIMESTAMP_OFFSET = 4
        private const val CSRC_COUNT_MASK = 0x0F
        private const val FLAG_PADDING = 0x20
        private const val FLAG_EXTENSION = 0x10
        private const val FU_START = 0x80
        private const val FU_END = 0x40
        private const val H264_TYPE_MASK = 0x1F
        private const val H264_NRI_MASK = 0xE0
        private const val H264_FU_A = 28
        private const val H264_STAP_A = 24
        private const val HEVC_TYPE_MASK = 0x3F
        private const val HEVC_HEADER_KEEP_MASK = 0x81
        private const val HEVC_FU = 49
        private const val HEVC_AP = 48
        private const val H264_SPS_BYTE = 0x67
        private const val H264_PPS_BYTE = 0x68
        private const val H264_IDR_BYTE = 0x65
        private const val HEVC_VPS_BYTE = 0x40
        private const val HEVC_SPS_BYTE = 0x42
        private const val HEVC_PPS_BYTE = 0x44

        /** Strips the fixed header, CSRCs, extension and padding. */
        public fun payload(packet: ByteArray): ByteArray {
            require(packet.size >= HEADER_SIZE) { "RTP packet is too short" }
            val b0 = packet[0].toInt() and BYTE_MASK
            require(b0 ushr 6 == 2) { "Unsupported RTP version" }
            var offset = HEADER_SIZE + (b0 and CSRC_COUNT_MASK) * 4
            require(packet.size >= offset) { "RTP CSRC header exceeds packet length" }
            if (b0 and FLAG_EXTENSION != 0) {
                require(packet.size >= offset + 4) { "RTP extension header exceeds packet length" }
                val words =
                    ((packet[offset + 2].toInt() and BYTE_MASK) shl 8) or (packet[offset + 3].toInt() and BYTE_MASK)
                offset += 4 + words * 4
                require(packet.size >= offset) { "RTP extension payload exceeds packet length" }
            }
            var end = packet.size
            if (b0 and FLAG_PADDING != 0) {
                require(end > offset) { "RTP padding set without payload" }
                val padding = packet[end - 1].toInt() and BYTE_MASK
                require(padding in 1..(end - offset)) { "Invalid RTP padding length" }
                end -= padding
            }
            return packet.copyOfRange(offset, end)
        }

        private inline fun require(
            ok: Boolean,
            message: () -> String,
        ) {
            if (!ok) throw RtpException(message())
        }

        /**
         * Codec from the first payload byte, using only unambiguous markers (fragment/aggregation
         * types and parameter sets); null for a plain slice, so callers wait for the next packet.
         */
        public fun detectCodec(payload0: Byte): VideoCodec? {
            val b = payload0.toInt() and BYTE_MASK
            val h264Type = b and H264_TYPE_MASK
            val hevcType = (b ushr 1) and HEVC_TYPE_MASK
            return when {
                h264Type == H264_FU_A || h264Type == H264_STAP_A -> VideoCodec.H264
                b == H264_SPS_BYTE || b == H264_PPS_BYTE || b == H264_IDR_BYTE -> VideoCodec.H264
                hevcType == HEVC_FU || hevcType == HEVC_AP -> VideoCodec.H265
                b == HEVC_VPS_BYTE || b == HEVC_SPS_BYTE || b == HEVC_PPS_BYTE -> VideoCodec.H265
                else -> null
            }
        }
    }
}

/** A malformed RTP packet. */
public class RtpException(
    message: String,
) : VtmException(message)
