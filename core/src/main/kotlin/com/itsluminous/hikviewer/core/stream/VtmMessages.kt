package com.itsluminous.hikviewer.core.stream

import java.io.ByteArrayOutputStream

/**
 * The two protobuf messages exchanged with a VTM relay, hand-encoded.
 *
 * Only varint (wire type 0) and length-delimited (wire type 2) fields occur, so a 60-line codec is
 * simpler and smaller than pulling in protobuf-lite and a `.proto` toolchain for two messages.
 */
public object VtmMessages {
    /** Client version string the official app of that era sent; the relay checks nothing beyond presence. */
    public const val CLIENT_VERSION: String = "v3.6.3.20221124"

    /** `StreamInfoReq`: the stream URL, an optional relay-assigned key after a redirect, client versions. */
    public fun streamInfoRequest(
        streamUrl: String,
        vtmStreamKey: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeString(1, streamUrl)
        if (!vtmStreamKey.isNullOrEmpty()) out.writeString(2, vtmStreamKey)
        out.writeString(FIELD_CLIENT_VERSION, CLIENT_VERSION)
        out.writeVarintField(FIELD_FLAGS, 0)
        out.writeString(FIELD_CLIENT_VERSION_2, CLIENT_VERSION)
        return out.toByteArray()
    }

    /** `KeepAliveReq`: just the stream session id. */
    public fun keepaliveRequest(streamSession: String): ByteArray =
        ByteArrayOutputStream().apply { writeString(1, streamSession) }.toByteArray()

    /** Parses a `StreamInfoRsp` body. Unknown fields are ignored; repeated fields keep the last value. */
    public fun parseStreamInfoResponse(body: ByteArray): StreamInfoResponse {
        val fields = readFields(body)
        return StreamInfoResponse(
            result = fields.lastInt(1),
            dataKey = fields.lastInt(2),
            streamHead = fields.lastString(FIELD_STREAM_HEAD),
            streamSession = fields.lastString(FIELD_STREAM_SSN),
            vtmStreamKey = fields.lastString(FIELD_VTM_STREAM_KEY),
            redirectUrl = fields.lastString(FIELD_STREAM_URL),
            aesMd5 = fields.lastString(FIELD_AES_MD5),
        )
    }

    private sealed class Value {
        data class Int64(
            val value: Long,
        ) : Value()

        class Bytes(
            val value: ByteArray,
        ) : Value()
    }

    private fun readFields(data: ByteArray): Map<Int, List<Value>> {
        val values = mutableMapOf<Int, MutableList<Value>>()
        var pos = 0
        while (pos < data.size) {
            val (key, afterKey) = readVarint(data, pos)
            val field = (key ushr 3).toInt()
            when ((key and WIRE_TYPE_MASK).toInt()) {
                WIRE_VARINT -> {
                    val (value, next) = readVarint(data, afterKey)
                    values.getOrPut(field) { mutableListOf() } += Value.Int64(value)
                    pos = next
                }
                WIRE_LENGTH_DELIMITED -> {
                    val (length, start) = readVarint(data, afterKey)
                    val end = start + length.toInt()
                    if (length < 0 || end > data.size) throw VtmException("StreamInfoRsp field exceeds payload")
                    values.getOrPut(field) { mutableListOf() } += Value.Bytes(data.copyOfRange(start, end))
                    pos = end
                }
                else -> throw VtmException("Unsupported StreamInfoRsp wire type: ${key and WIRE_TYPE_MASK}")
            }
        }
        return values
    }

    private fun Map<Int, List<Value>>.lastInt(field: Int): Long? = (this[field]?.lastOrNull() as? Value.Int64)?.value

    private fun Map<Int, List<Value>>.lastString(field: Int): String? =
        (this[field]?.lastOrNull() as? Value.Bytes)?.value?.toString(Charsets.UTF_8)

    private fun readVarint(
        data: ByteArray,
        start: Int,
    ): Pair<Long, Int> {
        var pos = start
        var shift = 0
        var value = 0L
        while (pos < data.size && shift < MAX_VARINT_SHIFT) {
            val b = data[pos++].toInt()
            value = value or ((b and VARINT_PAYLOAD_MASK).toLong() shl shift)
            if (b and VARINT_CONTINUE == 0) return value to pos
            shift += VARINT_BITS
        }
        throw VtmException("Malformed protobuf varint")
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (true) {
            val chunk = (v and VARINT_PAYLOAD_MASK.toLong()).toInt()
            v = v ushr VARINT_BITS
            if (v == 0L) {
                write(chunk)
                return
            }
            write(chunk or VARINT_CONTINUE)
        }
    }

    private fun ByteArrayOutputStream.writeVarintField(
        field: Int,
        value: Long,
    ) {
        writeVarint(((field shl 3) or WIRE_VARINT).toLong())
        writeVarint(value)
    }

    private fun ByteArrayOutputStream.writeString(
        field: Int,
        value: String,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(((field shl 3) or WIRE_LENGTH_DELIMITED).toLong())
        writeVarint(bytes.size.toLong())
        write(bytes)
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_TYPE_MASK = 0x07L
    private const val VARINT_PAYLOAD_MASK = 0x7F
    private const val VARINT_CONTINUE = 0x80
    private const val VARINT_BITS = 7
    private const val MAX_VARINT_SHIFT = 64
    private const val FIELD_CLIENT_VERSION = 3
    private const val FIELD_FLAGS = 4
    private const val FIELD_CLIENT_VERSION_2 = 6
    private const val FIELD_STREAM_HEAD = 3
    private const val FIELD_STREAM_SSN = 4
    private const val FIELD_VTM_STREAM_KEY = 5
    private const val FIELD_STREAM_URL = 7
    private const val FIELD_AES_MD5 = 9
}

/** The fields of `StreamInfoRsp` this client uses. */
public data class StreamInfoResponse(
    /** 0 on success; 6106 means the channel has no stream (empty recorder input). */
    val result: Long?,
    /** Relay-side data key id (informational). */
    val dataKey: Long?,
    /** Stream header hint (informational). */
    val streamHead: String?,
    /** Stream session id, echoed in keepalives. */
    val streamSession: String?,
    /** Key to present when following [redirectUrl]. */
    val vtmStreamKey: String?,
    /** Another relay to connect to instead, when set together with a non-zero [result]. */
    val redirectUrl: String?,
    /** MD5 of the AES key as the cloud knows it (informational). */
    val aesMd5: String?,
) {
    /** True when the relay wants us on another server. */
    val isRedirect: Boolean get() =
        (result ?: 0L) != 0L &&
            !redirectUrl.isNullOrEmpty() &&
            !vtmStreamKey.isNullOrEmpty()

    /** True when the relay said this channel produces no stream. */
    val isNoStream: Boolean get() = result == NO_STREAM_RESULT

    /** Well-known result codes. */
    public companion object {
        /** `result` for a channel that exists but has no video (empty DVR input). */
        public const val NO_STREAM_RESULT: Long = 6106
    }
}
