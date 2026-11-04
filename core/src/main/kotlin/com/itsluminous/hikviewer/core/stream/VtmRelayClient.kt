package com.itsluminous.hikviewer.core.stream

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/** Opens a TCP connection; replaced by an in-memory pair in tests. */
public fun interface SocketFactory {
    /** A connected socket to [host]:[port], failing after [connectTimeoutMillis]. */
    public fun connect(
        host: String,
        port: Int,
        connectTimeoutMillis: Int,
    ): Socket

    /** Built-in factories. */
    public companion object {
        /** Plain `java.net.Socket`. The relay speaks a bespoke protocol, not TLS. */
        public val PLAIN: SocketFactory =
            SocketFactory { host, port, timeout ->
                Socket().apply { connect(InetSocketAddress(host, port), timeout) }
            }
    }
}

/** The relay stopped sending: no bytes for the read timeout. The caller decides whether to reconnect. */
public class VtmStallException(
    message: String,
    cause: Throwable? = null,
) : VtmException(message, cause)

/**
 * Blocking client for one VTM relay connection.
 *
 * Flow: [connect] → [start] sends `StreamInfoReq` and waits for `StreamInfoRsp` (following up to
 * three relay redirects) → [readStreamPacket] returns media packets and answers the relay's
 * keepalives inline → [close]. All methods block; [com.itsluminous.hikviewer.core.stream.LiveStream]
 * drives them from `Dispatchers.IO`. [close] may be called from any thread to abort a blocked read.
 */
public class VtmRelayClient(
    streamUrl: String,
    private val sockets: SocketFactory = SocketFactory.PLAIN,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val keepaliveIntervalMillis: Long = DEFAULT_KEEPALIVE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    /** Current relay URL; changes when the relay redirects. */
    public var streamUrl: String = streamUrl
        private set

    /** The last `StreamInfoRsp`, after [start]. */
    public var streamInfo: StreamInfoResponse? = null
        private set

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var sequence = 0
    private var lastKeepaliveAt = 0L

    @Volatile private var closed = false

    /**
     * True once the relay announced the end of a playback window (a frame on
     * [VtmChannel.PLAYBACK_END]). [readStreamPacket] returns null for that frame; the caller
     * checks this flag to tell a clean end from a control message.
     */
    public var endOfStream: Boolean = false
        private set

    /** True after [connect] until [close]. */
    public val isConnected: Boolean get() = socket != null

    /** Connects to the host and port in [streamUrl]. No-op when already connected. */
    public fun connect() {
        if (socket != null) return
        val (host, port) = VtmUrl.endpoint(streamUrl)
        val s = sockets.connect(host, port, connectTimeoutMillis)
        s.soTimeout = readTimeoutMillis
        s.tcpNoDelay = true
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    /**
     * Requests the stream and returns the relay's answer. Follows a redirect (non-zero result with a
     * new URL and key) at most [maxRedirects] times; gives up after [maxControlPackets] unrelated
     * control packets.
     */
    public fun start(
        maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
        maxControlPackets: Int = DEFAULT_MAX_CONTROL,
    ): StreamInfoResponse {
        var redirects = 0
        var streamKey: String? = null
        while (true) {
            connect()
            send(VtmMessages.streamInfoRequest(streamUrl, streamKey), VtmMessageCode.STREAMINFO_REQ)
            val info = awaitStreamInfo(maxControlPackets)
            streamInfo = info
            if (!info.isRedirect || redirects >= maxRedirects) return info
            close()
            closed = false
            streamUrl = info.redirectUrl!!
            streamKey = info.vtmStreamKey
            redirects++
        }
    }

    private fun awaitStreamInfo(maxControlPackets: Int): StreamInfoResponse {
        repeat(maxControlPackets) {
            val frame = readFrame()
            if (frame.messageCode == VtmMessageCode.STREAMINFO_RSP) {
                return VtmMessages.parseStreamInfoResponse(frame.body)
            }
        }
        throw VtmException("Timed out waiting for VTM stream info response")
    }

    /**
     * Next media payload, blocking up to the read timeout. Returns null when the frame read was a
     * control message (keepalives are answered here) or the playback end marker (see
     * [endOfStream]), so callers simply loop. Throws
     * [VtmStallException] when nothing arrives within the read timeout and [VtmException] when the
     * relay closes the connection.
     */
    public fun readStreamPacket(): ByteArray? {
        val session = streamInfo?.streamSession
        if (session != null && clock() - lastKeepaliveAt >= keepaliveIntervalMillis) {
            sendKeepalive(session, VtmMessageCode.KEEPALIVE_REQ)
        }
        val frame = readFrame()
        return when {
            frame.messageCode == VtmMessageCode.KEEPALIVE_REQ -> {
                session?.let { sendKeepalive(it, VtmMessageCode.KEEPALIVE_RSP) }
                null
            }
            frame.isStream -> frame.body
            frame.channel == VtmChannel.PLAYBACK_END -> {
                endOfStream = true
                null
            }
            else -> null
        }
    }

    /** Sends one frame on the message channel and returns its sequence number. */
    public fun send(
        body: ByteArray,
        messageCode: Int,
        channel: Int = VtmChannel.MESSAGE,
    ): Int {
        val out = output ?: throw VtmException("VTM socket is not connected")
        val seq = sequence
        out.write(VtmFrame(channel, seq, messageCode, body).encode())
        out.flush()
        sequence = (sequence + 1) and SEQUENCE_MASK
        return seq
    }

    private fun sendKeepalive(
        session: String,
        code: Int,
    ) {
        send(VtmMessages.keepaliveRequest(session), code)
        lastKeepaliveAt = clock()
    }

    /** Reads one complete frame. */
    public fun readFrame(): VtmFrame {
        val header = VtmFrame.decodeHeader(readExactly(VtmFrame.HEADER_SIZE))
        val body = readExactly(header.length)
        return VtmFrame(header.channel, header.sequence, header.messageCode, body)
    }

    private fun readExactly(length: Int): ByteArray {
        val input = input ?: throw VtmException("VTM socket is not connected")
        val buffer = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val n = readChunk(input, buffer, filled)
            if (n < 0) throw VtmException(if (closed) CLOSED_BY_CLIENT else CLOSED_BY_RELAY)
            filled += n
        }
        return buffer
    }

    private fun readChunk(
        input: InputStream,
        buffer: ByteArray,
        offset: Int,
    ): Int = try {
        input.read(buffer, offset, buffer.size - offset)
    } catch (e: SocketTimeoutException) {
        throw VtmStallException("No data from the relay for ${readTimeoutMillis}ms", e)
    } catch (e: EOFException) {
        throw VtmException(CLOSED_BY_RELAY, e)
    } catch (e: SocketException) {
        // A reset is the relay hanging up without a FIN (seen when it closes right after the
        // handshake); it means the same as an orderly close to the caller.
        val reset = e.message?.contains("reset", ignoreCase = true) == true
        throw VtmException(
            when {
                closed -> CLOSED_BY_CLIENT
                reset -> CLOSED_BY_RELAY
                else -> "VTM socket error: ${e.message}"
            },
            e,
        )
    }

    /** Closes the socket; safe to call twice and from another thread while a read blocks. */
    override fun close() {
        closed = true
        val s = socket
        socket = null
        input = null
        output = null
        s?.runCatching { close() }
    }

    /** Defaults chosen from the spike measurements (first frame 1.4–3.2 s, GOP bursts ~2 s apart). */
    public companion object {
        /** TCP connect timeout. */
        public const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 10_000

        /** Read timeout; the cloud sends a GOP every ~2 s, so 10 s of silence is a stall. */
        public const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 10_000

        /** Keepalive interval, matching the reference client. */
        public const val DEFAULT_KEEPALIVE_MILLIS: Long = 5_000
        private const val DEFAULT_MAX_REDIRECTS = 3
        private const val DEFAULT_MAX_CONTROL = 20
        private const val SEQUENCE_MASK = 0xFFFF
        private const val CLOSED_BY_CLIENT = "VTM socket closed by client"
        private const val CLOSED_BY_RELAY = "VTM socket closed while reading packet"
    }
}
