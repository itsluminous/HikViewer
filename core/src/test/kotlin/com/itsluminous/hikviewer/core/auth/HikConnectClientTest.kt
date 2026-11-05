package com.itsluminous.hikviewer.core.auth

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.UnsafeDomainException
import com.itsluminous.hikviewer.core.crypto.Md5
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URLDecoder

class HikConnectClientTest {
    private val server = MockWebServer()
    private lateinit var client: HikConnectClient

    @BeforeEach
    fun start() {
        server.start()
        client =
            HikConnectClient(
                featureCodes = { FEATURE_CODE },
                http = OkHttpClient(),
                hosts = { _ -> server.url("/") },
            )
    }

    @AfterEach
    fun stop() = server.shutdown()

    @Nested
    inner class Login {
        @Test
        fun `happy path returns session from loginSession and loginArea`() = runTest {
            server.enqueue(json(LOGIN_OK))

            val session = client.login("91XXXXXXXXXX", "secret", Region.INDIA)

            assertThat(session.sessionId).isEqualTo("sess.jwt.sig")
            assertThat(session.refreshSessionId).isEqualTo("rf-1")
            assertThat(session.apiDomain).isEqualTo("apiiindia.hik-connect.com")
            assertThat(session.userId).isEqualTo("user-1")
            assertThat(session.areaId).isEqualTo(244)
            assertThat(session.toString()).doesNotContain("sess.jwt")
        }

        @Test
        fun `sends the app headers and the md5 password form`() = runTest {
            server.enqueue(json(LOGIN_OK))

            client.login("91XXXXXXXXXX", "secret", Region.INDIA)

            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).isEqualTo("/v3/users/login/v2")
            assertThat(request.getHeader("clientType")).isEqualTo("55")
            assertThat(request.getHeader("featureCode")).isEqualTo(FEATURE_CODE)
            assertThat(request.getHeader("lang")).isEqualTo("en-US")
            assertThat(request.getHeader("language")).isEqualTo("en-US")
            assertThat(request.getHeader("appId")).isEqualTo("ys7")
            assertThat(request.getHeader("clientNo")).isEqualTo("web_site")
            assertThat(request.getHeader("User-Agent")).isEqualTo("okhttp/3.12.1")
            assertThat(request.getHeader("sessionId")).isNull()
            val form = request.form()
            assertThat(form["account"]).isEqualTo("91XXXXXXXXXX")
            assertThat(form["password"]).isEqualTo(Md5.hex("secret"))
            assertThat(form["featureCode"]).isEqualTo(FEATURE_CODE)
            assertThat(form["password"]).isNotEqualTo("secret")
        }

        @ParameterizedTest
        @ValueSource(ints = [1100, 101100])
        fun `region redirect retries once on loginArea apiDomain`(code: Int) = runTest {
            server.enqueue(json(redirect(code, "apiiindia.hik-connect.com")))
            server.enqueue(json(LOGIN_OK))

            val session = client.login("91XXXXXXXXXX", "secret", Region.GLOBAL)

            assertThat(session.apiDomain).isEqualTo("apiiindia.hik-connect.com")
            assertThat(server.requestCount).isEqualTo(2)
        }

        @Test
        fun `second redirect is a loop`() = runTest {
            server.enqueue(json(redirect(1100, "apiiindia.hik-connect.com")))
            server.enqueue(json(redirect(1100, "apiisgp.hik-connect.com")))

            val error = assertThrows<LoginError.RedirectLoop> { client.login("a", "b", Region.GLOBAL) }

            assertThat(error.hosts)
                .containsExactly(
                    "api.hik-connect.com",
                    "apiiindia.hik-connect.com",
                    "apiisgp.hik-connect.com",
                ).inOrder()
        }

        @Test
        fun `redirect to a host outside the allow-list is refused`() = runTest {
            server.enqueue(json(redirect(1100, "evil.example.com")))

            assertThrows<UnsafeDomainException> { client.login("a", "b", Region.GLOBAL) }
        }

        @ParameterizedTest
        @ValueSource(ints = [1013, 1014, 1226])
        fun `wrong credentials carry the country-code hint`(code: Int) = runTest {
            server.enqueue(json(meta(code, "wrong email or password")))

            val error = assertThrows<LoginError.InvalidCredentials> { client.login("a", "b") }

            assertThat(error.hint).contains("country code")
            assertThat(error.message).contains("region")
        }

        @Test
        fun `captcha is a typed verification error`() = runTest {
            server.enqueue(json(meta(1015, "captcha")))

            val error = assertThrows<LoginError.NeedsVerification> { client.login("a", "b") }

            assertThat(error.kind).isEqualTo(VerificationKind.CAPTCHA)
            assertThat(error.metaCode).isEqualTo(1015)
        }

        @Test
        fun `mfa and terminal binding are typed verification errors`() = runTest {
            server.enqueue(json(meta(6002, "mfa")))
            server.enqueue(json(meta(1069, "terminal")))

            val sms = assertThrows<LoginError.NeedsVerification> { client.login("a", "b") }
            val terminal = assertThrows<LoginError.NeedsVerification> { client.login("a", "b") }

            assertThat(sms.kind).isEqualTo(VerificationKind.SMS_CODE)
            assertThat(terminal.kind).isEqualTo(VerificationKind.TERMINAL_BINDING)
        }

        @Test
        fun `unknown meta code is a server error with the message`() = runTest {
            server.enqueue(json(meta(9999, "boom")))

            val error = assertThrows<LoginError.Server> { client.login("a", "b") }

            assertThat(error.metaCode).isEqualTo(9999)
            assertThat(error.serverMessage).isEqualTo("boom")
        }

        @Test
        fun `200 without loginSession is a server error`() = runTest {
            server.enqueue(json("""{"meta":{"code":200}}"""))

            assertThrows<LoginError.Server> { client.login("a", "b") }
        }

        @Test
        fun `non-json body is a network error`() = runTest {
            server.enqueue(MockResponse().setBody("<html>502</html>").setResponseCode(502))

            assertThrows<LoginError.Network> { client.login("a", "b") }
        }

        @Test
        fun `connection failure is a network error`() = runTest {
            server.shutdown()

            assertThrows<LoginError.Network> { client.login("a", "b") }
        }
    }

    @Nested
    inner class RefreshAndLogout {
        @Test
        fun `refresh puts refreshSessionId and returns rotated tokens`() = runTest {
            server.enqueue(
                json("""{"meta":{"code":200},"sessionInfo":{"sessionId":"new.jwt","refreshSessionId":"rf-2"}}"""),
            )

            val renewed = client.refreshSession(SESSION)

            assertThat(renewed.sessionId).isEqualTo("new.jwt")
            assertThat(renewed.refreshSessionId).isEqualTo("rf-2")
            assertThat(renewed.apiDomain).isEqualTo(SESSION.apiDomain)
            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("PUT")
            assertThat(request.path).isEqualTo("/v3/apigateway/login")
            val form = request.form()
            assertThat(form["refreshSessionId"]).isEqualTo("rf-1")
            assertThat(form["featureCode"]).isEqualTo(FEATURE_CODE)
        }

        @Test
        fun `rejected refresh is SessionExpired`() = runTest {
            server.enqueue(json(meta(401, "expired")).setResponseCode(401))

            val error = assertThrows<LoginError.SessionExpired> { client.refreshSession(SESSION) }

            assertThat(error.code).isEqualTo(401)
        }

        @Test
        fun `logout deletes with the session header and ignores failures`() = runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            client.logout(SESSION)

            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("DELETE")
            assertThat(request.path).isEqualTo("/v3/users/logout/v2")
            assertThat(request.getHeader("sessionId")).isEqualTo(SESSION.sessionId)
        }
    }

    @Nested
    inner class AuthenticatedGet {
        @Test
        fun `get adds query and session header and returns body`() = runTest {
            server.enqueue(json("""{"meta":{"code":200},"x":1}"""))

            val body = client.get(SESSION, "/v3/thing", mapOf("a" to "1", "b" to "two words"))

            assertThat(body["x"].toString()).isEqualTo("1")
            val request = server.takeRequest()
            assertThat(request.requestUrl!!.queryParameter("a")).isEqualTo("1")
            assertThat(request.requestUrl!!.queryParameter("b")).isEqualTo("two words")
            assertThat(request.getHeader("sessionId")).isEqualTo(SESSION.sessionId)
        }

        @Test
        fun `http 401 and meta 401 are unauthorized`() = runTest {
            server.enqueue(json(meta(401, "x")).setResponseCode(401))
            server.enqueue(json(meta(401, "x")))

            val a = assertThrows<ApiException> { client.get(SESSION, "/v3/thing") }
            val b = assertThrows<ApiException> { client.get(SESSION, "/v3/thing") }

            assertThat(a.isUnauthorized).isTrue()
            assertThat(b.isUnauthorized).isTrue()
            assertThat(a.message).contains("/v3/thing")
        }

        @Test
        fun `meta error with http 200 is an ApiException`() = runTest {
            server.enqueue(json(meta(2009, "device offline")))

            val error = assertThrows<ApiException> { client.get(SESSION, "/v3/thing") }

            assertThat(error.metaCode).isEqualTo(2009)
            assertThat(error.isUnauthorized).isFalse()
        }
    }

    private companion object {
        const val FEATURE_CODE = "0123456789abcdef0123456789abcdef"
        val SESSION = Session("sess.jwt.sig", "rf-1", "apiiindia.hik-connect.com", "user-1", 244)
        const val LOGIN_OK = """{"meta":{"code":200,"message":"ok"},
            "loginSession":{"sessionId":"sess.jwt.sig","rfSessionId":"rf-1"},
            "loginUser":{"userId":"user-1","areaId":244},
            "loginArea":{"areaId":244,"areaName":"India","apiDomain":"apiiindia.hik-connect.com"}}"""

        fun meta(
            code: Int,
            message: String,
        ) = """{"meta":{"code":$code,"message":"$message"}}"""

        fun redirect(
            code: Int,
            host: String,
        ) = """{"meta":{"code":$code},"loginArea":{"apiDomain":"$host"}}"""

        fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

        fun RecordedRequest.form(): Map<String, String> = body.readUtf8().split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
