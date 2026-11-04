package com.itsluminous.hikviewer.core.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HikConnectSessionTest {
    private val server = MockWebServer()
    private lateinit var client: HikConnectClient
    private val changes = mutableListOf<Session>()

    @BeforeEach
    fun start() {
        server.start()
        client = HikConnectClient({ "fc" }, OkHttpClient()) { _ -> server.url("/") }
    }

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `get refreshes once on 401 and retries with the new session`() = runTest {
        server.enqueue(json("""{"meta":{"code":401}}""").setResponseCode(401))
        server.enqueue(json("""{"meta":{"code":200},"sessionInfo":{"sessionId":"s2","refreshSessionId":"r2"}}"""))
        server.enqueue(json("""{"meta":{"code":200},"ok":true}"""))
        val session = HikConnectSession(client, INITIAL) { changes += it }

        val body = session.get("/v3/x")

        assertThat(body["ok"].toString()).isEqualTo("true")
        assertThat(session.current.sessionId).isEqualTo("s2")
        assertThat(changes.map { it.sessionId }).containsExactly("s2")
        server.takeRequest()
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("sessionId")).isEqualTo("s2")
    }

    @Test
    fun `a second 401 after refresh propagates`() = runTest {
        server.enqueue(json("""{"meta":{"code":401}}""").setResponseCode(401))
        server.enqueue(json("""{"meta":{"code":200},"sessionInfo":{"sessionId":"s2","refreshSessionId":"r2"}}"""))
        server.enqueue(json("""{"meta":{"code":401}}""").setResponseCode(401))
        val session = HikConnectSession(client, INITIAL)

        val error = assertThrows<ApiException> { session.get("/v3/x") }

        assertThat(error.isUnauthorized).isTrue()
    }

    @Test
    fun `non-401 errors are not retried`() = runTest {
        server.enqueue(json("""{"meta":{"code":2009}}"""))
        val session = HikConnectSession(client, INITIAL)

        assertThrows<ApiException> { session.get("/v3/x") }

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `refresh is skipped when another caller already renewed`() = runTest {
        server.enqueue(json("""{"meta":{"code":200},"sessionInfo":{"sessionId":"s2","refreshSessionId":"r2"}}"""))
        val session = HikConnectSession(client, INITIAL)

        session.refresh()
        val stale = INITIAL
        val result = session.withRetry { s -> if (s === stale) throw ApiException(401, 401, "/x") else s }

        assertThat(result.sessionId).isEqualTo("s2")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `logout delegates to the client`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val session = HikConnectSession(client, INITIAL)

        session.logout()

        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    private companion object {
        val INITIAL = Session("s1", "r1", "apiiindia.hik-connect.com", null, null)

        fun json(body: String) = MockResponse().setBody(body)
    }
}
