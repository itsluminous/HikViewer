package com.itsluminous.hikviewer.core.testutil

import com.itsluminous.hikviewer.core.stream.VtmChannel
import com.itsluminous.hikviewer.core.stream.VtmFrame
import com.itsluminous.hikviewer.core.stream.VtmMessageCode
import com.itsluminous.hikviewer.core.stream.VtmMessages
import com.itsluminous.hikviewer.core.stream.VtmProtocolTest
import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A scripted VTM relay on localhost. Each accepted connection runs [script] on its own thread with
 * a [Connection] handle; frames the client sends are recorded in [received].
 */
class FakeRelayServer(
    private val script: Connection.() -> Unit,
) : Closeable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val host: String = "127.0.0.1"
    val received = CopyOnWriteArrayList<VtmFrame>()
    val connections = CopyOnWriteArrayList<Connection>()
    private val threads = CopyOnWriteArrayList<Thread>()

    init {
        thread(isDaemon = true, name = "fake-relay-accept") {
            try {
                while (true) {
                    val socket = server.accept()
                    val connection = Connection(socket)
                    connections += connection
                    threads +=
                        thread(isDaemon = true, name = "fake-relay-conn") {
                            try {
                                connection.script()
                            } catch (_: Exception) {
                                // client went away; nothing to do
                            } finally {
                                connection.closed.countDown()
                            }
                        }
                }
            } catch (_: Exception) {
                // server closed
            }
        }
    }

    fun awaitConnections(
        count: Int,
        timeoutMillis: Long = 5_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (connections.size < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
        check(connections.size >= count) { "expected $count connections, got ${connections.size}" }
    }

    override fun close() {
        server.close()
        connections.forEach { it.socket.runCatching { close() } }
    }

    inner class Connection(
        val socket: Socket,
    ) {
        private val input = DataInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        private var sequence = 0
        val closed = CountDownLatch(1)

        /** Reads the next frame from the client (blocks). */
        fun readFrame(): VtmFrame {
            val header = ByteArray(VtmFrame.HEADER_SIZE)
            input.readFully(header)
            val h = VtmFrame.decodeHeader(header)
            val body = ByteArray(h.length).also { input.readFully(it) }
            return VtmFrame(h.channel, h.sequence, h.messageCode, body).also { received += it }
        }

        fun send(frame: VtmFrame) {
            output.write(frame.encode())
            output.flush()
        }

        fun sendControl(
            messageCode: Int,
            body: ByteArray,
        ) = send(VtmFrame(VtmChannel.MESSAGE, sequence++, messageCode, body))

        fun sendStream(payload: ByteArray) = send(VtmFrame(VtmChannel.STREAM, sequence++, 0, payload))

        /** Reads the StreamInfoReq and answers with a successful StreamInfoRsp. */
        fun handshake(
            session: String = "ssn",
            result: Long = 0,
            redirectUrl: String? = null,
            key: String? = null,
        ) {
            val req = readFrame()
            check(req.messageCode == VtmMessageCode.STREAMINFO_REQ) { "expected StreamInfoReq, got ${req.messageCode}" }
            var body = VtmProtocolTest.varint(1, result) + VtmProtocolTest.str(4, session)
            if (redirectUrl != null) body += VtmProtocolTest.str(7, redirectUrl)
            if (key != null) body += VtmProtocolTest.str(5, key)
            sendControl(VtmMessageCode.STREAMINFO_RSP, body)
        }

        fun requestText(): String = String(received.last().body, Charsets.UTF_8)

        fun awaitClosed(timeoutMillis: Long = 5_000) = closed.await(timeoutMillis, TimeUnit.MILLISECONDS)

        fun close() = socket.close()

        fun keepaliveBody(session: String) = VtmMessages.keepaliveRequest(session)
    }
}
