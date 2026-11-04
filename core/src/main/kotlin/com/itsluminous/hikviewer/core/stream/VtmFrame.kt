package com.itsluminous.hikviewer.core.stream

/**
 * The 8-byte framing every message on a VTM relay TCP connection uses:
 * `magic 0x24 | channel | length (u16) | sequence (u16) | message code (u16)` followed by `length`
 * body bytes. Control messages are protobuf; stream packets carry the media payload as is.
 */
public data class VtmFrame(
    /** Logical channel; see [VtmChannel]. */
    val channel: Int,
    /** Sender's sequence number (wraps at 16 bits). */
    val sequence: Int,
    /** Message code; see [VtmMessageCode]. Zero for stream data. */
    val messageCode: Int,
    /** Body bytes. */
    val body: ByteArray,
) {
    /** True for the two channels that carry media. */
    val isStream: Boolean get() = channel == VtmChannel.STREAM || channel == VtmChannel.ENCRYPTED_STREAM

    override fun equals(other: Any?): Boolean = other is VtmFrame &&
        channel == other.channel &&
        sequence == other.sequence &&
        messageCode == other.messageCode &&
        body.contentEquals(other.body)

    override fun hashCode(): Int = (channel * 31 + sequence) * 31 + messageCode + body.contentHashCode()

    /** Serialises header and body. */
    public fun encode(): ByteArray {
        if (body.size > MAX_BODY) throw VtmException("VTM packet body is too large")
        val out = ByteArray(HEADER_SIZE + body.size)
        out[0] = MAGIC.toByte()
        out[1] = channel.toByte()
        out.putU16(OFFSET_LENGTH, body.size)
        out.putU16(OFFSET_SEQUENCE, sequence)
        out.putU16(OFFSET_MESSAGE_CODE, messageCode)
        body.copyInto(out, HEADER_SIZE)
        return out
    }

    /** Constants and parsers. */
    public companion object {
        /** Header length in bytes. */
        public const val HEADER_SIZE: Int = 8

        /** First byte of every frame. */
        public const val MAGIC: Int = 0x24
        private const val MAX_BODY = 0xFFFF
        private const val U16_MASK = 0xFFFF

        /** Parses a header; the body length is returned so the caller knows how much to read. */
        public fun decodeHeader(header: ByteArray): Header {
            if (header.size != HEADER_SIZE) throw VtmException("VTM header must be exactly 8 bytes")
            if (header[0].toInt() and BYTE_MASK != MAGIC) throw VtmException("VTM magic byte not found")
            val channel = header[1].toInt() and BYTE_MASK
            if (!VtmChannel.isKnown(channel)) throw VtmException("Unknown VTM channel: 0x${channel.toString(16)}")
            return Header(
                channel = channel,
                length = u16(header, OFFSET_LENGTH),
                sequence = u16(header, OFFSET_SEQUENCE),
                messageCode = u16(header, OFFSET_MESSAGE_CODE),
            )
        }

        /** Parses a complete frame (header + body in one buffer). */
        public fun decode(packet: ByteArray): VtmFrame {
            if (packet.size < HEADER_SIZE) throw VtmException("VTM packet shorter than its header")
            val header = decodeHeader(packet.copyOfRange(0, HEADER_SIZE))
            val body = packet.copyOfRange(HEADER_SIZE, packet.size)
            if (body.size != header.length) {
                throw VtmException("VTM packet length mismatch: header=${header.length} body=${body.size}")
            }
            return VtmFrame(header.channel, header.sequence, header.messageCode, body)
        }

        private fun u16(
            b: ByteArray,
            at: Int,
        ): Int = ((b[at].toInt() and BYTE_MASK) shl 8 or (b[at + 1].toInt() and BYTE_MASK)) and U16_MASK

        private fun ByteArray.putU16(
            at: Int,
            value: Int,
        ) {
            this[at] = (value ushr 8).toByte()
            this[at + 1] = value.toByte()
        }

        private const val BYTE_MASK = 0xFF
        private const val OFFSET_LENGTH = 2
        private const val OFFSET_SEQUENCE = 4
        private const val OFFSET_MESSAGE_CODE = 6
    }

    /** Decoded header fields. */
    public data class Header(
        /** Logical channel. */
        val channel: Int,
        /** Body length in bytes. */
        val length: Int,
        /** Sequence number. */
        val sequence: Int,
        /** Message code. */
        val messageCode: Int,
    )
}

/** Channel ids used in the VTM frame header. */
public object VtmChannel {
    /** Control messages (protobuf). */
    public const val MESSAGE: Int = 0x00

    /** Media packets. */
    public const val STREAM: Int = 0x01

    /**
     * End of a playback window: the relay sends one control frame here after the last picture of
     * a `/playback?` stream and then closes the socket. Live streams never use it.
     */
    public const val PLAYBACK_END: Int = 0x02

    /** Encrypted control channel (not used by this client, accepted when seen). */
    public const val ENCRYPTED_MESSAGE: Int = 0x0A

    /** Encrypted media channel. */
    public const val ENCRYPTED_STREAM: Int = 0x0B

    /** True for the five channel ids above. */
    public fun isKnown(channel: Int): Boolean = channel == MESSAGE ||
        channel == STREAM ||
        channel == PLAYBACK_END ||
        channel == ENCRYPTED_MESSAGE ||
        channel == ENCRYPTED_STREAM
}

/** Message codes carried in the VTM frame header. */
public object VtmMessageCode {
    /** Relay asks the client to confirm it is alive; the client sends one every few seconds too. */
    public const val KEEPALIVE_REQ: Int = 0x132

    /** Keepalive answer. */
    public const val KEEPALIVE_RSP: Int = 0x133

    /** Client's `StreamInfoReq`. */
    public const val STREAMINFO_REQ: Int = 0x13B

    /** Relay's `StreamInfoRsp`. */
    public const val STREAMINFO_RSP: Int = 0x13C

    /** Unsolicited stream info update. */
    public const val STREAMINFO_NOTIFY: Int = 0x13D
}
