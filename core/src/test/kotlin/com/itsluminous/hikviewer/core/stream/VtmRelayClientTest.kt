package com.itsluminous.hikviewer.core.stream

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.testutil.FakeRelayServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Timeout(20)
class VtmRelayClientTest {
    private fun url(server: FakeRelayServer) = "ysproto://${server.host}:${server.port}/live?dev=X&chn=1&stream=2"

    @Test
    fun `start sends StreamInfoReq with the url and parses the response`() {
        FakeRelayServer { handshake(session = "S1") }.use { server ->
            VtmRelayClient(url(server)).use { client ->
                val info = client.start()

                assertThat(info.streamSession).isEqualTo("S1")
                assertThat(info.result).isEqualTo(0)
                assertThat(client.isConnected).isTrue()
                val request = server.received.single()
                assertThat(request.channel).isEqualTo(VtmChannel.MESSAGE)
                assertThat(request.sequence).isEqualTo(0)
                assertThat(request.messageCode).isEqualTo(VtmMessageCode.STREAMINFO_REQ)
                assertThat(String(request.body, Charsets.ISO_8859_1)).contains(url(server))
            }
        }
    }

    @Test
    fun `stream packets are returned and keepalive requests answered`() {
        val gotKeepaliveRsp = CountDownLatch(1)
        FakeRelayServer {
            handshake(session = "S1")
            sendStream(byteArrayOf(1, 2, 3))
            sendControl(VtmMessageCode.KEEPALIVE_REQ, ByteArray(0))
            val rsp = readFrame()
            if (rsp.messageCode == VtmMessageCode.KEEPALIVE_RSP && rsp.body.contentEquals(keepaliveBody("S1"))) {
                gotKeepaliveRsp.countDown()
            }
            sendControl(VtmMessageCode.STREAMINFO_NOTIFY, ByteArray(0))
            sendStream(byteArrayOf(4))
            send(VtmFrame(VtmChannel.ENCRYPTED_STREAM, 9, 0, byteArrayOf(5)))
            closed.await()
        }.use { server ->
            VtmRelayClient(url(server), keepaliveIntervalMillis = Long.MAX_VALUE, clock = { 0L }).use { client ->
                client.start()

                assertThat(client.readStreamPacket()).isEqualTo(byteArrayOf(1, 2, 3))
                assertThat(client.readStreamPacket()).isNull() // keepalive request
                assertThat(gotKeepaliveRsp.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(client.readStreamPacket()).isNull() // notify
                assertThat(client.readStreamPacket()).isEqualTo(byteArrayOf(4))
                assertThat(client.readStreamPacket()).isEqualTo(byteArrayOf(5))
            }
        }
    }

    @Test
    fun `client sends a keepalive when the interval elapsed`() {
        var now = 0L
        FakeRelayServer {
            handshake(session = "S1")
            val ka = readFrame()
            if (ka.messageCode == VtmMessageCode.KEEPALIVE_REQ) sendStream(byteArrayOf(7))
            closed.await()
        }.use { server ->
            VtmRelayClient(url(server), keepaliveIntervalMillis = 5_000, clock = { now }).use { client ->
                client.start()
                now = 6_000

                assertThat(client.readStreamPacket()).isEqualTo(byteArrayOf(7))
                assertThat(server.received.last().body).isEqualTo(VtmMessages.keepaliveRequest("S1"))
            }
        }
    }

    @Test
    fun `redirect reconnects to the new url with the stream key`() {
        var redirectTarget = ""
        var first = true
        FakeRelayServer {
            if (first) {
                first = false
                handshake(session = "S0", result = 1, redirectUrl = redirectTarget, key = "KEY1")
            } else {
                handshake(session = "S2")
            }
        }.use { server ->
            redirectTarget = "ysproto://${server.host}:${server.port}/live?redirected=1"
            VtmRelayClient(url(server)).use { client ->
                val info = client.start()

                assertThat(info.streamSession).isEqualTo("S2")
                assertThat(client.streamUrl).isEqualTo(redirectTarget)
                assertThat(server.received).hasSize(2)
                val second = String(server.received[1].body, Charsets.ISO_8859_1)
                assertThat(second).contains("redirected=1")
                assertThat(second).contains("KEY1")
            }
        }
    }

    @Test
    fun `redirect loop gives up after the limit`() {
        var target = ""
        FakeRelayServer { handshake(result = 1, redirectUrl = target, key = "K") }.use { server ->
            target = url(server)
            VtmRelayClient(url(server)).use { client ->
                val info = client.start(maxRedirects = 2)

                assertThat(info.isRedirect).isTrue()
                assertThat(server.received).hasSize(3)
            }
        }
    }

    @Test
    fun `too many control packets without stream info fails`() {
        FakeRelayServer {
            readFrame()
            repeat(5) { sendControl(VtmMessageCode.STREAMINFO_NOTIFY, ByteArray(0)) }
            closed.await()
        }.use { server ->
            VtmRelayClient(url(server)).use { client ->
                val error = assertThrows<VtmException> { client.start(maxControlPackets = 3) }
                assertThat(error.message).contains("stream info")
            }
        }
    }

    @Test
    fun `silence is a stall and a closed socket is an error`() {
        FakeRelayServer {
            handshake()
            closed.await()
        }.use { server ->
            VtmRelayClient(url(server), readTimeoutMillis = 200).use { client ->
                client.start()
                assertThrows<VtmStallException> { client.readStreamPacket() }
            }
        }
        FakeRelayServer {
            handshake()
            close()
        }.use { server ->
            VtmRelayClient(url(server)).use { client ->
                client.start()
                val error = assertThrows<VtmException> { client.readStreamPacket() }
                assertThat(error).isNotInstanceOf(VtmStallException::class.java)
                assertThat(error.message).contains("closed")
            }
        }
    }

    @Test
    fun `close from another thread aborts a blocked read`() {
        FakeRelayServer {
            handshake()
            closed.await()
        }.use { server ->
            val client = VtmRelayClient(url(server), readTimeoutMillis = 10_000)
            client.start()
            val closer =
                Thread {
                    Thread.sleep(100)
                    client.close()
                }.also { it.start() }

            val error = assertThrows<VtmException> { client.readStreamPacket() }

            closer.join()
            assertThat(error.message).contains("closed")
            assertThat(client.isConnected).isFalse()
            client.close() // idempotent
        }
    }

    @Test
    fun `methods before connect fail clearly and connect refuses bad hosts`() {
        val client = VtmRelayClient("ysproto://127.0.0.1:1/live")
        assertThrows<VtmException> { client.readFrame() }
        assertThrows<VtmException> { client.send(ByteArray(0), 1) }
        assertThrows<java.io.IOException> { client.connect() }
        assertThrows<VtmException> { VtmRelayClient("ysproto://nohost/live").connect() }
    }
}
