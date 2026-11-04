package com.itsluminous.hikviewer.core.playback

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.HikConnectClient
import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.auth.Session
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.devices.VtmPageList
import com.itsluminous.hikviewer.core.devices.VtmRelay
import com.itsluminous.hikviewer.core.devices.VtmResource
import com.itsluminous.hikviewer.core.stream.Clock
import com.itsluminous.hikviewer.core.stream.StreamException
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.core.stream.VideoCodec
import com.itsluminous.hikviewer.core.stream.VtduTokenProvider
import com.itsluminous.hikviewer.core.stream.VtmChannel
import com.itsluminous.hikviewer.core.stream.VtmFrame
import com.itsluminous.hikviewer.core.stream.VtmUrl
import com.itsluminous.hikviewer.core.testutil.FakeRelayServer
import com.itsluminous.hikviewer.core.testutil.Fixtures
import kotlinx.coroutines.flow.first
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
import java.time.LocalDateTime
import java.util.Base64

@Timeout(30)
class PlaybackStreamTest {
    private val http = MockWebServer()
    private lateinit var tokens: VtduTokenProvider
    private val camera = Camera("FAKE0000001", 1, "cam", hasSignal = true, isOnline = true)
    private val window =
        PlaybackWindow(LocalDateTime.of(2026, 11, 4, 17, 13, 51), LocalDateTime.of(2026, 11, 4, 17, 14, 51))

    /** Clear HEVC 720p Annex-B from the playback spike (a 10 s window; 11 GOPs). */
    private val nals: List<ByteArray> = PlaybackFixture.nals()

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

    private fun pageList(server: FakeRelayServer) = VtmPageList(
        listOf(VtmResource("FAKE0000001", 1, "R1", "cam", "?biz=1", VtmRelay(server.host, server.port))),
    )

    private fun playback(
        server: FakeRelayServer,
        config: PlaybackStreamConfig = PlaybackStreamConfig(),
    ) = PlaybackStream({ pageList(server) }, tokens, config, clock, sleep = { sleeps += it })

    private fun FakeRelayServer.Connection.sendRecording(gops: Int = Int.MAX_VALUE) {
        var sent = 0
        for (nal in nals) {
            if (PlaybackFixture.isVps(nal) && sent++ >= gops) return
            sendStream(PlaybackFixture.ps(nal))
        }
    }

    private fun FakeRelayServer.Connection.sendEndOfWindow() =
        send(VtmFrame(VtmChannel.PLAYBACK_END, 0, 0, byteArrayOf(1)))

    @Test
    fun `plays the window as hevc pictures and ends on the channel 2 marker`() = runBlocking {
        FakeRelayServer {
            handshake()
            sendRecording()
            sendEndOfWindow()
            closed.await()
        }.use { server ->
            val units = playback(server).open(camera, window, null) { statuses += it }.toList()

            assertThat(units.size).isAtLeast(270)
            assertThat(units.first().isKeyframe).isTrue()
            assertThat(units.first().codec).isEqualTo(VideoCodec.H265)
            assertThat(units.first().config?.vps).isNotNull()
            assertThat(units.count { it.isKeyframe }).isEqualTo(11)
            assertThat(statuses)
                .containsExactly(StreamStatus.Connecting(1), StreamStatus.Streaming, StreamStatus.Ended)
                .inOrder()
            val request = String(server.received.first().body, Charsets.ISO_8859_1)
            assertThat(request).contains("/playback?biz=1&dev=FAKE0000001&chn=1&stream=1")
            assertThat(request).contains("&begin=20261104T171351Z&end=20261104T171451Z&serial=FAKE0000001&cln=9")
            assertThat(request).doesNotContain("weakstream")
            assertThat(request).contains("ssn=TOKEN1")
            assertThat(sleeps).isEmpty()
            server.connections.first().awaitClosed()
        }
    }

    @Test
    fun `result 6103 is retried on a fresh connection after the busy delay`() = runBlocking {
        var connections = 0
        FakeRelayServer {
            connections++
            if (connections == 1) {
                handshake(result = 6103)
                closed.await()
            } else {
                handshake()
                sendRecording(gops = 1)
                sendEndOfWindow()
                closed.await()
            }
        }.use { server ->
            val config = PlaybackStreamConfig(retryDelayMillis = 2_000)
            val units = playback(server, config).open(camera, window, null) { statuses += it }.toList()

            assertThat(units).isNotEmpty()
            assertThat(sleeps).containsExactly(2_000L)
            assertThat(server.connections).hasSize(2)
            val busy = statuses.filterIsInstance<StreamStatus.Reconnecting>().single()
            assertThat(busy.cause).isInstanceOf(RelayBusyException::class.java)
            assertThat(busy.delayMillis).isEqualTo(2_000L)
            assertThat(statuses.last()).isEqualTo(StreamStatus.Ended)
        }
    }

    @Test
    fun `a relay that stays busy fails after maxAttempts`() = runBlocking {
        FakeRelayServer {
            handshake(result = 6103)
            closed.await()
        }.use { server ->
            val config = PlaybackStreamConfig(retryDelayMillis = 10, maxAttempts = 3)

            assertThrows<RelayBusyException> { playback(server, config).open(camera, window, null).toList() }

            assertThat(sleeps).containsExactly(10L, 10L).inOrder()
            assertThat(server.connections).hasSize(3)
        }
    }

    @Test
    fun `a relay that closes after delivering pictures ends the window cleanly`() = runBlocking {
        FakeRelayServer {
            handshake()
            sendRecording(gops = 2)
            close()
        }.use { server ->
            val units = playback(server).open(camera, window, null) { statuses += it }.toList()

            assertThat(units.count { it.isKeyframe }).isEqualTo(2)
            assertThat(statuses.last()).isEqualTo(StreamStatus.Ended)
            assertThat(sleeps).isEmpty()
        }
    }

    @Test
    fun `offline channel, unknown rejections and a missing resource are terminal`() = runBlocking {
        FakeRelayServer { handshake(result = 6106) }.use { server ->
            assertThrows<StreamException.ChannelOffline> { playback(server).open(camera, window, null).first() }
        }
        http.enqueue(MockResponse().setBody("""{"retcode":0,"tokens":["TOKEN1"]}"""))
        FakeRelayServer { handshake(result = 7) }.use { server ->
            assertThrows<StreamException.RelayRejected> { playback(server).open(camera, window, null).first() }
        }
        FakeRelayServer { }.use { server ->
            val other = Camera("FAKE0000001", 9, "cam", hasSignal = true, isOnline = true)
            assertThrows<StreamException.ChannelNotStreamable> { playback(server).open(other, window, null).first() }
            assertThat(server.connections).isEmpty()
        }
    }

    @Test
    fun `wrong verification code is terminal`() = runBlocking {
        val encrypted =
            com.itsluminous.hikviewer.core.stream.NalDecryptorTest
                .readUnits("h264_nals_whole.bin")
        FakeRelayServer {
            handshake()
            repeat(3) { encrypted.forEach { nal -> sendStream(PlaybackFixture.ps(nal)) } }
            closed.await()
        }.use { server ->
            assertThrows<StreamException.WrongVerificationCode> {
                playback(server).open(camera, window, "WRONG").first()
            }
        }
    }

    @Test
    fun `playback url formats recorder-local times with a literal Z and no extra parameters`() {
        val resource = VtmResource("DEV1", 3, "R", "cam", "?biz=1&x=y", VtmRelay("relay.ezvizlife.com", 10554))
        val url =
            VtmUrl.buildPlayback(
                resource,
                PlaybackWindow(LocalDateTime.of(2026, 1, 2, 3, 4, 5), LocalDateTime.of(2026, 1, 2, 3, 5, 5)),
                channelSerial = "DEV1",
                vtduToken = "tok",
                timestampMillis = 42,
            )

        val common = "cln=9&isp=0&auth=1&ssn=tok&vip=0&timestamp=42"
        assertThat(url).isEqualTo(
            "ysproto://relay.ezvizlife.com:10554/playback?biz=1&x=y&dev=DEV1&chn=3&stream=1" +
                "&begin=20260102T030405Z&end=20260102T030505Z&serial=DEV1&$common",
        )
        assertThat(VtmUrl.endpoint(url)).isEqualTo("relay.ezvizlife.com" to 10554)
        // Live URLs are unchanged by the playback addition.
        assertThat(VtmUrl.build(resource, StreamQuality.SUB, "tok", 42))
            .endsWith("/live?biz=1&x=y&dev=DEV1&chn=3&stream=2&$common")
    }

    @Test
    fun `a window must end after it begins`() {
        val t = LocalDateTime.of(2026, 1, 1, 0, 0)
        assertThrows<IllegalArgumentException> { PlaybackWindow(t, t) }
        assertThat(PlaybackWindow(t, t.plusSeconds(30)).duration.seconds).isEqualTo(30)
    }

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
}

/** The decrypted HEVC capture of the spike, split into NAL units and re-packed as MPEG-PS. */
object PlaybackFixture {
    private val START = byteArrayOf(0, 0, 0, 1)

    /** NAL units (without start codes) of `pb_ch1_winend.h265`. */
    fun nals(): List<ByteArray> {
        val data = Fixtures.bytes("pb_ch1_winend.h265")
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            val startCode = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte()
            if (startCode && data[i + 3] == 1.toByte()) {
                starts += i
                i += START.size
            } else {
                i++
            }
        }
        return starts.mapIndexed { n, s ->
            val end = starts.getOrNull(n + 1) ?: data.size
            data.copyOfRange(s + START.size, end)
        }
    }

    /** True for an HEVC VPS (NAL type 32), which opens every GOP in the capture. */
    fun isVps(nal: ByteArray): Boolean = (nal[0].toInt() ushr 1) and 0x3F == 32

    /** One MPEG-PS pack with a single bounded video PES carrying [nal] in Annex-B form. */
    fun ps(nal: ByteArray): ByteArray {
        val es = START + nal
        val pack = byteArrayOf(0, 0, 1, 0xBA.toByte(), 0x44) + ByteArray(8) + byteArrayOf(0xF8.toByte())
        val length = 3 + es.size
        return pack +
            byteArrayOf(0, 0, 1, 0xE0.toByte(), (length ushr 8).toByte(), length.toByte(), 0x80.toByte(), 0, 0) +
            es
    }
}
