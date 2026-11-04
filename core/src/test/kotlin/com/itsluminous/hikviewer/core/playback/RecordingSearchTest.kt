package com.itsluminous.hikviewer.core.playback

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.ApiException
import com.itsluminous.hikviewer.core.auth.HikConnectClient
import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.auth.Session
import com.itsluminous.hikviewer.core.devices.Camera
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Base64
import java.util.zip.Deflater

class RecordingSearchTest {
    private val server = MockWebServer()
    private lateinit var search: RecordingSearch
    private val camera = Camera("FAKE0000001", 1, "cam", hasSignal = true, isOnline = true)

    @BeforeEach
    fun start() {
        server.start()
        val client = HikConnectClient({ "fc" }, OkHttpClient()) { _ -> server.url("/") }
        search = RecordingSearch(HikConnectSession(client, Session("s", "r", "apiiindia.hik-connect.com", null, null)))
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    /** The reference DVR's answer: `begin`/`end`, ISO-T, recorder-local, continuous recording. */
    private val dvrBody =
        """
        {"meta":{"code":200,"message":"操作成功"},
         "records":[
           {"channelType":"D","begin":"2026-11-03T10:19:41","end":"2026-11-04T00:03:04","type":"TIMING"},
           {"channelType":"D","begin":"2026-11-04T00:03:05","end":"2026-11-04T09:12:00","type":"TIMING"},
           {"channelType":"D","begin":"2026-11-04T09:30:00","end":"2026-11-04T09:31:00","type":"EVENT"}
         ],
         "isFinished":"TRUE"}
        """.trimIndent()

    @Test
    fun `lists segments with the v1 query the recorder answers`() = runTest {
        server.enqueue(json(dvrBody))

        val segments =
            search.segments(camera, LocalDateTime.of(2026, 11, 3, 10, 0, 0), LocalDateTime.of(2026, 11, 4, 10, 0, 0))

        assertThat(segments).hasSize(3)
        assertThat(segments[0].begin).isEqualTo(LocalDateTime.of(2026, 11, 3, 10, 19, 41))
        assertThat(segments[0].end).isEqualTo(LocalDateTime.of(2026, 11, 4, 0, 3, 4))
        assertThat(segments[0].type).isEqualTo(RecordSegment.TYPE_TIMING)
        assertThat(segments[2].type).isEqualTo(RecordSegment.TYPE_EVENT)
        assertThat(segments[2].duration.seconds).isEqualTo(60)
        assertThat(segments[0].contains(LocalDateTime.of(2026, 11, 3, 12, 0))).isTrue()
        assertThat(segments[0].contains(LocalDateTime.of(2026, 11, 4, 0, 3, 4))).isFalse()

        val request = server.takeRequest()
        assertThat(request.path).startsWith("/v3/streaming/records?")
        val url = request.requestUrl!!
        assertThat(url.queryParameter("deviceSerial")).isEqualTo("FAKE0000001")
        assertThat(url.queryParameter("channelNo")).isEqualTo("1")
        assertThat(url.queryParameter("channelSerial")).isEqualTo("FAKE0000001")
        assertThat(url.queryParameter("startTime")).isEqualTo("2026-11-03 10:00:00")
        assertThat(url.queryParameter("stopTime")).isEqualTo("2026-11-04 10:00:00")
        assertThat(url.queryParameter("size")).isEqualTo("200")
        assertThat(request.getHeader("sessionId")).isEqualTo("s")
    }

    @Test
    fun `segmentsOn clips to the recorder day and drops segments outside it`() = runTest {
        server.enqueue(json(dvrBody))

        val day = search.segmentsOn(camera, LocalDate.of(2026, 11, 4))

        assertThat(day.map { it.begin to it.end })
            .containsExactly(
                LocalDateTime.of(2026, 11, 4, 0, 0) to LocalDateTime.of(2026, 11, 4, 0, 3, 4),
                LocalDateTime.of(2026, 11, 4, 0, 3, 5) to LocalDateTime.of(2026, 11, 4, 9, 12),
                LocalDateTime.of(2026, 11, 4, 9, 30) to LocalDateTime.of(2026, 11, 4, 9, 31),
            ).inOrder()
        val url = server.takeRequest().requestUrl!!
        assertThat(url.queryParameter("startTime")).isEqualTo("2026-11-04 00:00:00")
        assertThat(url.queryParameter("stopTime")).isEqualTo("2026-11-04 23:59:59")
    }

    @Test
    fun `follows unfinished pages from the last segment end`() = runTest {
        val page1 = """{"B":"2026-11-04 01:00:00","E":"2026-11-04 02:00:00","T":"TIMING"}"""
        server.enqueue(json("""{"records":[$page1],"isFinished":"FALSE"}"""))
        val page2 = """{"startTime":"2026-11-04T03:00:00","stopTime":"2026-11-04T04:00:00"}"""
        server.enqueue(json("""{"records":[$page2],"isFinished":"TRUE"}"""))

        val segments =
            search.segments(camera, LocalDateTime.of(2026, 11, 4, 0, 0), LocalDateTime.of(2026, 11, 4, 23, 59, 59))

        assertThat(segments.map { it.begin.hour }).containsExactly(1, 3).inOrder()
        assertThat(segments[1].type).isEqualTo(RecordSegment.TYPE_TIMING)
        server.takeRequest()
        assertThat(server.takeRequest().requestUrl!!.queryParameter("startTime")).isEqualTo("2026-11-04 02:00:01")
    }

    @Test
    fun `records packed as base64 zlib json are inflated`() {
        val packed = deflate("""[{"begin":"2026-11-04T05:00:00","end":"2026-11-04T06:00:00","type":"TIMING"}]""")

        val segments = RecordingSearch.parseRecords(JsonPrimitive(packed))

        assertThat(segments).hasSize(1)
        assertThat(segments.single().begin.hour).isEqualTo(5)
        assertThat(RecordingSearch.parseRecords(JsonPrimitive(""))).isEmpty()
        assertThat(RecordingSearch.parseRecords(null)).isEmpty()
        assertThat(RecordingSearch.parseRecords(Json.parseToJsonElement("{}"))).isEmpty()
    }

    @Test
    fun `malformed items and payloads are rejected or skipped`() {
        val items =
            Json.parseToJsonElement(
                """
                [{"begin":"garbage","end":"2026-11-04T06:00:00"},
                 {"begin":"2026-11-04T06:00:00","end":"2026-11-04T05:00:00"},
                 {"begin":"2026-11-04T20261104T070000Z","end":"x"},
                 {"begin":"20261104T070000Z","end":"20261104T080000Z"},
                 7]
                """,
            )
        assertThat(RecordingSearch.parseRecords(items).map { it.begin.hour }).containsExactly(7)
        assertThrows<RecordingSearchException> {
            RecordingSearch.parseRecords(JsonPrimitive(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))))
        }
        assertThrows<RecordingSearchException> { RecordingSearch.parseRecords(JsonPrimitive(deflate("{}"))) }
    }

    @Test
    fun `an api error such as 2004 surfaces as ApiException`() = runTest {
        server.enqueue(json("""{"meta":{"code":2004,"message":"not supported"}}"""))

        val error =
            assertThrows<ApiException> {
                search.segmentsOn(camera, LocalDate.of(2026, 11, 4))
            }
        assertThat(error.metaCode).isEqualTo(2004)
    }

    private fun deflate(text: String): String {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray())
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}
