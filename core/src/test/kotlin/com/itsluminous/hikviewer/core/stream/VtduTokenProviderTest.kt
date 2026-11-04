package com.itsluminous.hikviewer.core.stream

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.HikConnectClient
import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.auth.Session
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class VtduTokenProviderTest {
    private val server = MockWebServer()
    private lateinit var session: HikConnectSession
    private var now = 0L

    @BeforeEach
    fun start() {
        server.start()
        val client = HikConnectClient({ "fc" }, OkHttpClient()) { _ -> server.url("/") }
        session = HikConnectSession(client, Session(jwt("sign-1"), "rf", "apiiindia.hik-connect.com", null, null))
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun provider(ttl: Long = 3_600_000) = VtduTokenProvider(session, { _ -> server.url("/") }, { now }, ttl)

    @Test
    fun `fetches from the derived auth host with ssid and sign and caches`() = runTest {
        server.enqueue(json("""{"retcode":0,"tokens":["T1","T2"]}"""))
        val provider = provider()

        assertThat(provider.token()).isEqualTo("T1")
        assertThat(provider.token()).isEqualTo("T1")

        assertThat(server.requestCount).isEqualTo(1)
        val url = server.takeRequest().requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/vtdutoken2")
        assertThat(url.queryParameter("ssid")).isEqualTo(session.current.sessionId)
        assertThat(url.queryParameter("sign")).isEqualTo("sign-1")
    }

    @Test
    fun `cache expires after the ttl and invalidate forces a refetch`() = runTest {
        server.enqueue(json("""{"retcode":0,"tokens":["T1"]}"""))
        server.enqueue(json("""{"retcode":"0","tokens":["T2"]}"""))
        server.enqueue(json("""{"retcode":0,"tokens":["T3"]}"""))
        val provider = provider(ttl = 1000)

        assertThat(provider.token()).isEqualTo("T1")
        now = 1000
        assertThat(provider.token()).isEqualTo("T2")
        provider.invalidate()
        assertThat(provider.token()).isEqualTo("T3")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `401 refreshes the session and retries with the new sign`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            json("""{"meta":{"code":200},"sessionInfo":{"sessionId":"${jwt("sign-2")}","refreshSessionId":"rf2"}}"""),
        )
        server.enqueue(json("""{"retcode":0,"tokens":["T9"]}"""))

        assertThat(provider().token()).isEqualTo("T9")

        server.takeRequest()
        assertThat(server.takeRequest().path).isEqualTo("/v3/apigateway/login")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("sign")).isEqualTo("sign-2")
    }

    @Test
    fun `persistent 401 gives up after the retries`() = runTest {
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(
                json("""{"meta":{"code":200},"sessionInfo":{"sessionId":"${jwt("s")}","refreshSessionId":"r"}}"""),
            )
        }

        val error = assertThrows<VtmException> { provider().token() }

        assertThat(error.message).contains("HTTP 401")
    }

    @Test
    fun `bad retcode and empty token lists are errors`() = runTest {
        server.enqueue(json("""{"retcode":1}"""))
        server.enqueue(json("""{"retcode":0,"tokens":[]}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

        assertThat(assertThrows<VtmException> { provider().token() }.message).contains("retcode 1")
        assertThat(assertThrows<VtmException> { provider().token() }.message).contains("empty")
        assertThat(assertThrows<VtmException> { provider().token() }.message).contains("HTTP 500")
    }

    @Test
    fun `falls back to systemConfigInfo authAddr when the api host has no region`() = runTest {
        val client = HikConnectClient({ "fc" }, OkHttpClient()) { _ -> server.url("/") }
        session = HikConnectSession(client, Session(jwt("sg"), "rf", "api.hik-connect.com", null, null))
        server.enqueue(
            json("""{"meta":{"code":200},"systemConfigInfo":{"authAddr":"https://sgpauth.ezvizlife.com/"}}"""),
        )
        server.enqueue(json("""{"retcode":0,"tokens":["T"]}"""))

        assertThat(provider().token()).isEqualTo("T")

        assertThat(server.takeRequest().path).startsWith("/v3/configurations/system/info")
    }

    @Test
    fun `authAddr null or foreign is rejected`() = runTest {
        val client = HikConnectClient({ "fc" }, OkHttpClient()) { _ -> server.url("/") }
        session = HikConnectSession(client, Session(jwt("sg"), "rf", "api.hik-connect.com", null, null))
        server.enqueue(json("""{"meta":{"code":200},"systemConfigInfo":{"authAddr":"https://null"}}"""))
        server.enqueue(json("""{"meta":{"code":200},"systemConfigInfo":{"authAddr":"https://auth.example.com"}}"""))

        assertThat(assertThrows<VtmException> { provider().token() }.message).contains("authAddr")
        assertThrows<com.itsluminous.hikviewer.core.UnsafeDomainException> { provider().token() }
    }

    @Test
    fun `derivedAuthHost and sessionSign helpers`() {
        assertThat(VtduTokenProvider.derivedAuthHost("apiiindia.hik-connect.com")).isEqualTo("indiaauth.ezvizlife.com")
        assertThat(VtduTokenProvider.derivedAuthHost("apiisgp.ezvizlife.com")).isEqualTo("sgpauth.ezvizlife.com")
        assertThat(VtduTokenProvider.derivedAuthHost("api.hik-connect.com")).isNull()
        assertThat(VtduTokenProvider.sessionSign(jwt("abc"))).isEqualTo("abc")
        assertThrows<VtmException> { VtduTokenProvider.sessionSign("not-a-jwt") }
        assertThrows<VtmException> { VtduTokenProvider.sessionSign("a.!!!.c") }
        assertThrows<VtmException> { VtduTokenProvider.sessionSign("a.${b64("""{"x":1}""")}.c") }
        assertThrows<VtmException> { VtduTokenProvider.sessionSign("a.${b64("[1]")}.c") }
    }

    private fun json(body: String) = MockResponse().setBody(body)

    companion object {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

        fun jwt(sign: String) = "${b64("""{"alg":"HS256"}""")}.${b64("""{"s":"$sign","exp":1}""")}.sig"
    }
}
