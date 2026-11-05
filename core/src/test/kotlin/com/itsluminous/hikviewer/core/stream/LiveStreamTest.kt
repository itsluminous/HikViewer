package com.itsluminous.hikviewer.core.stream

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.HikConnectClient
import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.auth.Session
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.VtmPageList
import com.itsluminous.hikviewer.core.devices.VtmRelay
import com.itsluminous.hikviewer.core.devices.VtmResource
import com.itsluminous.hikviewer.core.testutil.FakeRelayServer
import com.itsluminous.hikviewer.core.testutil.Fixtures
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.concurrent.Executors

@Timeout(30)
class LiveStreamTest {
    private val http = MockWebServer()
    private lateinit var tokens: VtduTokenProvider
    private val camera = Camera("FAKE0000001", 1, "cam", hasSignal = true, isOnline = true)
    private val ts = Fixtures.bytes("synthetic_sub_2s5.ts")

    @Volatile private var now = 0L
    private val clock = Clock { now }
    private val sleeps = mutableListOf<Long>()
    private val statuses = mutableListOf<StreamStatus>()

    @BeforeEach
    fun start() {
        http.start()
        val client = HikConnectClient({ "fc" }, OkHttpClient()) { _ -> http.url("/") }
        val jwt = "${b64("{}")}.${b64("""{"s":"sign"}""")}.x"
        val session = HikConnectSession(client, Session(jwt, "rf", "apiiindia.hik-connect.com", null, null))
        tokens = VtduTokenProvider(session, { _ -> http.url("/") })
        http.enqueue(MockResponse().setBody("""{"retcode":0,"tokens":["TOKEN1"]}"""))
    }

    @AfterEach
    fun stop() = http.shutdown()

    private fun pageList(
        server: FakeRelayServer,
        channel: Int = 1,
    ) = VtmPageList(
        listOf(VtmResource("FAKE0000001", channel, "R1", "cam", "?biz=1", VtmRelay(server.host, server.port))),
    )

    private fun live(
        server: FakeRelayServer,
        config: LiveStreamConfig = LiveStreamConfig(),
        channel: Int = 1,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = LiveStream(
        { pageList(server, channel) },
        tokens,
        config,
        clock,
        sleep = { sleeps += it },
        dispatcher = dispatcher,
    )

    private fun FakeRelayServer.Connection.sendTs(packets: Int = Int.MAX_VALUE) {
        var offset = 0
        var sent = 0
        while (offset < ts.size && sent < packets) {
            val end = minOf(offset + 7 * 188, ts.size)
            sendStream(ts.copyOfRange(offset, end))
            offset = end
            sent++
        }
    }

    @Test
    fun `streams pictures from the relay with the right url`() = runBlocking {
        FakeRelayServer {
            handshake()
            sendTs()
            closed.await()
        }.use { server ->
            val units = live(server).open(camera, StreamQuality.SUB, "ABCDEF1234") { statuses += it }.take(3).toList()

            assertThat(units).hasSize(3)
            assertThat(units.first().isKeyframe).isTrue()
            assertThat(units.first().codec).isEqualTo(VideoCodec.H264)
            assertThat(units.first().config).isNotNull()
            assertThat(statuses).containsExactly(StreamStatus.Connecting(1), StreamStatus.Streaming).inOrder()
            val request = String(server.received.first().body, Charsets.ISO_8859_1)
            assertThat(
                request,
            ).contains("ysproto://${server.host}:${server.port}/live?biz=1&dev=FAKE0000001&chn=1&stream=2")
            assertThat(request).contains("ssn=TOKEN1")
            assertThat(sleeps).isEmpty()
            server.connections.first().awaitClosed()
        }
    }

    @Test
    fun `the socket is read and pictures decrypted on the given dispatcher`() = runBlocking {
        val readers = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-reader") }
        try {
            FakeRelayServer {
                handshake()
                sendTs()
                closed.await()
            }.use { server ->
                val statusThreads = mutableListOf<String>()
                val stream =
                    live(server, dispatcher = readers.asCoroutineDispatcher())
                        .open(camera, StreamQuality.SUB, "ABCDEF1234") { statusThreads += Thread.currentThread().name }
                assertThat(stream.take(2).toList()).hasSize(2)
                // Every status callback (and so every read and decrypt) ran on the dispatcher's thread.
                assertThat(statusThreads).isNotEmpty()
                assertThat(statusThreads.toSet()).containsExactly("relay-reader")
            }
        } finally {
            readers.shutdownNow()
        }
    }

    @Test
    fun `camera missing from the page list is terminal`() = runBlocking {
        FakeRelayServer { }.use { server ->
            val error =
                assertThrows<StreamException.ChannelNotStreamable> {
                    live(server, channel = 2).open(camera, StreamQuality.SUB, null).first()
                }
            assertThat(error.message).contains("channel 1")
            assertThat(error.message).doesNotContain("FAKE0000001") // the serial stays out of logcat
            assertThat(server.connections).isEmpty()
        }
    }

    @Test
    fun `relay result 6106 is channel offline and other results are rejections`() = runBlocking {
        FakeRelayServer { handshake(result = 6106) }.use { server ->
            assertThrows<StreamException.ChannelOffline> { live(server).open(camera, StreamQuality.MAIN, null).first() }
        }
        http.enqueue(MockResponse().setBody("""{"retcode":0,"tokens":["TOKEN1"]}"""))
        FakeRelayServer { handshake(result = 5) }.use { server ->
            val error =
                assertThrows<StreamException.RelayRejected> {
                    live(
                        server,
                    ).open(camera, StreamQuality.MAIN, null).first()
                }
            assertThat(error.message).contains("5")
        }
    }

    @Test
    fun `wrong verification code is terminal`() = runBlocking {
        val encrypted = NalDecryptorTest.readUnits("h264_nals_whole.bin")
        FakeRelayServer {
            handshake()
            repeat(3) { encrypted.forEach { nal -> sendStream(ps(nal)) } }
            closed.await()
        }.use { server ->
            assertThrows<StreamException.WrongVerificationCode> {
                live(server).open(camera, StreamQuality.SUB, "WRONG").first()
            }
        }
    }

    @Test
    fun `a dropped connection reconnects after the backoff`() = runBlocking {
        var first = true
        FakeRelayServer {
            if (first) {
                first = false
                handshake()
                close()
            } else {
                handshake()
                sendTs()
                closed.await()
            }
        }.use { server ->
            val unit = live(server).open(camera, StreamQuality.SUB, null) { statuses += it }.first()

            assertThat(unit.isKeyframe).isTrue()
            assertThat(sleeps).containsExactly(1000L)
            assertThat(statuses.map { it::class.simpleName })
                .containsExactly("Connecting", "Reconnecting", "Connecting", "Streaming")
                .inOrder()
            val reconnecting = statuses[1] as StreamStatus.Reconnecting
            assertThat(reconnecting.attempt).isEqualTo(1)
            assertThat(reconnecting.cause).isInstanceOf(VtmException::class.java)
            assertThat(server.connections).hasSize(2)
        }
    }

    @Test
    fun `backoff doubles up to the cap and gives up after maxAttempts`() = runBlocking {
        FakeRelayServer {
            handshake()
            close()
        }.use { server ->
            val config = LiveStreamConfig(initialBackoffMillis = 100, maxBackoffMillis = 250, maxAttempts = 4)

            assertThrows<VtmException> { live(server, config).open(camera, StreamQuality.SUB, null).first() }

            assertThat(sleeps).containsExactly(100L, 200L, 250L).inOrder()
            assertThat(server.connections).hasSize(4)
        }
    }

    @Test
    fun `stall watchdog reconnects when packets arrive but no picture does`() = runBlocking {
        var first = true
        FakeRelayServer {
            handshake()
            if (first) {
                first = false
                // Audio-only RTP: packets flow, pictures never do.
                var seq = 0
                while (!socket.isClosed) {
                    sendStream(rtp(byteArrayOf(1, 2, 3), seq++, payloadType = 0))
                    now += 3_000
                    Thread.sleep(5)
                }
            } else {
                sendTs()
                closed.await()
            }
        }.use { server ->
            val config = LiveStreamConfig(stallTimeoutMillis = 10_000)

            val unit = live(server, config).open(camera, StreamQuality.SUB, null) { statuses += it }.first()

            assertThat(unit.isKeyframe).isTrue()
            val reconnecting = statuses.filterIsInstance<StreamStatus.Reconnecting>().single()
            assertThat(reconnecting.cause).isInstanceOf(VtmStallException::class.java)
            assertThat(reconnecting.cause.message).contains("No picture")
        }
    }

    @Test
    fun `silence from the relay is a stall through the socket timeout`() = runBlocking {
        var first = true
        FakeRelayServer {
            handshake()
            if (first) {
                first = false
                closed.await()
            } else {
                sendTs()
                closed.await()
            }
        }.use { server ->
            val config = LiveStreamConfig(readTimeoutMillis = 200)

            live(server, config).open(camera, StreamQuality.SUB, null) { statuses += it }.first()

            val reconnecting = statuses.filterIsInstance<StreamStatus.Reconnecting>().single()
            assertThat(reconnecting.cause).isInstanceOf(VtmStallException::class.java)
            server.connections.first().awaitClosed()
        }
    }

    @Test
    fun `a healthy connection resets the attempt counter`() = runBlocking {
        var connections = 0
        FakeRelayServer {
            connections++
            handshake()
            if (connections <= 2) {
                // 30 chunks (39 KB) hold the 13 KB IDR and three 8 KB P pictures, so a few pictures come
                // out before the relay drops us: ten need the third connection whatever the socket
                // buffering delivers.
                sendTs(packets = 30)
                close()
            } else {
                sendTs()
                closed.await()
            }
        }.use { server ->
            val units =
                live(
                    server,
                    LiveStreamConfig(maxAttempts = 2),
                ).open(camera, StreamQuality.SUB, null).take(10).toList()

            assertThat(units).hasSize(10)
            assertThat(sleeps).containsExactly(1000L, 1000L).inOrder()
        }
    }

    @Test
    fun `decode pipeline turns relay packets into pictures and reports the codec`() {
        val pipeline = DecodePipeline(null)
        var offset = 0
        val units = ArrayList<AccessUnit>()
        while (offset < ts.size) {
            val end = minOf(offset + 7 * 188, ts.size)
            units += pipeline.process(ts.copyOfRange(offset, end))
            offset = end
        }
        // 31 pictures in the file: the TS demuxer holds the last PES and the assembler holds the last picture.
        assertThat(units).hasSize(29)
        assertThat(pipeline.codec).isEqualTo(VideoCodec.H264)
        assertThat(pipeline.variant).isEqualTo(NalDecryptor.Variant.CLEAR)
        assertThat(DecodePipeline(null).process(byteArrayOf(0x47))).isEmpty()
    }

    /** One MPEG-PS pack with a single bounded video PES carrying [nal] in Annex-B form. */
    private fun ps(nal: ByteArray): ByteArray {
        val es = byteArrayOf(0, 0, 0, 1) + nal
        val pack = byteArrayOf(0, 0, 1, 0xBA.toByte(), 0x44) + ByteArray(8) + byteArrayOf(0xF8.toByte())
        val length = 3 + es.size
        return pack +
            byteArrayOf(0, 0, 1, 0xE0.toByte(), (length ushr 8).toByte(), length.toByte(), 0x80.toByte(), 0, 0) +
            es
    }

    private fun rtp(
        payload: ByteArray,
        sequence: Int,
        payloadType: Int = 96,
    ): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte()
        h[1] = payloadType.toByte()
        h[2] = (sequence ushr 8).toByte()
        h[3] = sequence.toByte()
        h[7] = 1
        h[11] = 1
        return h + payload
    }

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
}
