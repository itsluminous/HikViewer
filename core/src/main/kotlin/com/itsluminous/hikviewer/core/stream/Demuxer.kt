package com.itsluminous.hikviewer.core.stream

import java.io.ByteArrayOutputStream

/**
 * Turns the relay's media packets into NAL units.
 *
 * The cloud wraps video in one of three containers depending on device and firmware: RTP (one
 * packet per VTM frame), MPEG-PS or MPEG-TS. [AutoDemuxer] picks the right one from the first
 * packet; the three implementations share this interface so the rest of the pipeline is container
 * agnostic. Implementations are pure (no I/O) and not thread-safe.
 */
public interface Demuxer {
    /** Parses one relay packet and returns every NAL completed by it (often none). */
    public fun feed(packet: ByteArray): List<NalUnit>

    /** Releases NALs held back waiting for a container boundary (end of stream). */
    public fun flush(): List<NalUnit> = emptyList()

    /** The codec when the container announced it (RTP payload inspection, TS stream type), else null. */
    public val codecHint: VideoCodec?

    /** Diagnostics: packets dropped as malformed, duplicate, late or because of a gap. */
    public val droppedPackets: Int

    /** Diagnostics: elementary-stream bytes that belonged to no NAL (PS/TS only). */
    public val discardedBytes: Int get() = 0
}

/** Detects the container from the first packet and delegates to it. */
public class AutoDemuxer : Demuxer {
    private var delegate: Demuxer? = null

    /** The container chosen, or null before the first packet. */
    public val container: Container? get() =
        delegate?.let {
            when (it) {
                is RtpDemuxer -> Container.RTP
                is TsDemuxer -> Container.MPEG_TS
                else -> Container.MPEG_PS
            }
        }

    override val codecHint: VideoCodec? get() = delegate?.codecHint
    override val droppedPackets: Int get() = delegate?.droppedPackets ?: 0
    override val discardedBytes: Int get() = delegate?.discardedBytes ?: 0

    override fun feed(packet: ByteArray): List<NalUnit> {
        val d = delegate ?: detect(packet).also { delegate = it }
        return d.feed(packet)
    }

    override fun flush(): List<NalUnit> = delegate?.flush().orEmpty()

    private fun detect(packet: ByteArray): Demuxer {
        val rtpVersion = if (packet.isEmpty()) 0 else (packet[0].toInt() and BYTE_MASK) ushr RTP_VERSION_SHIFT
        val tsAligned =
            packet.isNotEmpty() && packet[0] == TsDemuxer.SYNC_BYTE && packet.size % TsDemuxer.PACKET_SIZE == 0
        return when {
            packet.size >= RtpDemuxer.HEADER_SIZE && rtpVersion == RTP_VERSION -> RtpDemuxer()
            tsAligned -> TsDemuxer()
            else -> PsDemuxer()
        }
    }

    /** Media containers the cloud is known to use. */
    public enum class Container { RTP, MPEG_PS, MPEG_TS }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val RTP_VERSION_SHIFT = 6
        const val RTP_VERSION = 2
    }
}

/**
 * Splits an Annex-B elementary stream into NAL units across chunk boundaries. The last, possibly
 * incomplete NAL is held back until the next start code or an explicit [flush]. Every NAL emitted
 * carries the [timestamp] current when it was emitted, so callers flush before changing it (a
 * picture's NALs share one PTS, so this is exact for PS/TS). Bytes that arrive before any start
 * code when nothing is held back (the tail of a NAL whose head was never seen, or was flushed too
 * early) belong to no NAL; they are dropped and counted in [discardedBytes].
 */
internal class AnnexBSplitter {
    private val buffer = ByteArrayOutputStream()

    /** Timestamp stamped on the NALs produced next. */
    var timestamp: Long? = null

    /** Bytes dropped because they preceded the first start code of a fresh buffer. */
    var discardedBytes: Int = 0
        private set

    fun append(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): List<NalUnit> {
        buffer.write(bytes, from, to - from)
        val data = buffer.toByteArray()
        val starts = startCodes(data)
        if (starts.size < 2) return emptyList()
        discardedBytes += headless(data, starts[0])
        val out = ArrayList<NalUnit>(starts.size - 1)
        for (i in 0 until starts.size - 1) {
            nalBetween(data, starts[i], starts[i + 1])?.let { out += NalUnit(it, timestamp) }
        }
        buffer.reset()
        buffer.write(data, starts.last(), data.size - starts.last())
        return out
    }

    /** Emits the held-back NAL (the PES ended) and clears the buffer. */
    fun flush(): List<NalUnit> {
        val data = buffer.toByteArray()
        buffer.reset()
        val starts = startCodes(data)
        if (starts.isEmpty()) {
            discardedBytes += headless(data, data.size)
            return emptyList()
        }
        discardedBytes += headless(data, starts[0])
        val out = ArrayList<NalUnit>(starts.size)
        for (i in starts.indices) {
            val end = if (i + 1 < starts.size) starts[i + 1] else data.size
            nalBetween(data, starts[i], end)?.let { out += NalUnit(it, timestamp) }
        }
        return out
    }

    /** Bytes held back, for buffer caps. */
    val pendingBytes: Int get() = buffer.size()

    fun reset() {
        buffer.reset()
    }

    /** Bytes before [firstStart] that are not the leading zero run of a 4-byte start code (`00 00 00 01`). */
    private fun headless(
        data: ByteArray,
        firstStart: Int,
    ): Int {
        var zeros = 0
        while (zeros < firstStart && data[zeros] == 0.toByte()) zeros++
        return firstStart - zeros
    }

    private fun nalBetween(
        data: ByteArray,
        start: Int,
        nextStart: Int,
    ): ByteArray? {
        val begin = start + START_CODE_LENGTH
        var end = nextStart
        // A 4-byte start code ahead leaves a trailing zero on this NAL; drop trailing zeros.
        while (end > begin && data[end - 1] == 0.toByte()) end--
        return if (end <= begin) null else data.copyOfRange(begin, end)
    }

    companion object {
        const val START_CODE_LENGTH = 3

        /** Offsets of every `00 00 01` in [data]. */
        fun startCodes(data: ByteArray): IntArray {
            val out = ArrayList<Int>()
            var i = 0
            while (i + 2 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                    out += i
                    i += START_CODE_LENGTH
                } else {
                    i++
                }
            }
            return out.toIntArray()
        }
    }
}
