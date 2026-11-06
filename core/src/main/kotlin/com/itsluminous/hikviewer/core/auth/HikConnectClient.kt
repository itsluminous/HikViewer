package com.itsluminous.hikviewer.core.auth

import com.itsluminous.hikviewer.core.CloudDomains
import com.itsluminous.hikviewer.core.crypto.Md5
import com.itsluminous.hikviewer.core.json.CloudJson
import com.itsluminous.hikviewer.core.json.int
import com.itsluminous.hikviewer.core.json.metaCode
import com.itsluminous.hikviewer.core.json.metaMessage
import com.itsluminous.hikviewer.core.json.obj
import com.itsluminous.hikviewer.core.json.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A cloud call that came back with an HTTP error or a non-200 `meta.code`.
 *
 * `metaCode` 401 (or HTTP 401) means the session is gone; [HikConnectSession] turns that into a
 * refresh-and-retry. Anything else is surfaced to the caller as is.
 */
public class ApiException(
    /** HTTP status of the response. */
    public val httpStatus: Int,
    /** `meta.code` from the body, when the body was a cloud envelope. */
    public val metaCode: Int?,
    /** Request path, for diagnostics (never includes query values). */
    public val path: String,
    /** Server message, if any. */
    public val serverMessage: String? = null,
) : IOException("$path: HTTP $httpStatus, meta ${metaCode ?: "-"}${serverMessage?.let { " ($it)" } ?: ""}") {
    /** True when the server rejected the session. */
    public val isUnauthorized: Boolean get() = httpStatus == HTTP_UNAUTHORIZED || metaCode == HTTP_UNAUTHORIZED

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}

/**
 * Hik-Connect REST client: login, session refresh, logout and authenticated GETs.
 *
 * Sends the header set of the official Android app (as reverse-engineered by cloud-cam-viewer).
 * Some endpoints answer with truncated bodies when headers are missing, so the full set is sent on
 * every request. Blocking OkHttp calls run on [Dispatchers.IO]; parsing is done on the caller.
 */
public class HikConnectClient(
    private val featureCodes: FeatureCodeStore,
    private val http: OkHttpClient = defaultHttpClient(),
    private val hosts: HostResolver = HostResolver.HTTPS,
) {
    /**
     * Logs in with [account] exactly as typed (phone numbers must already carry the country code)
     * and the plain [password]. Starts at [region]'s host and follows one region redirect.
     */
    public suspend fun login(
        account: String,
        password: String,
        region: Region = Region.DEFAULT,
    ): Session {
        val visited = mutableListOf<String>()
        var host = region.apiHost
        while (true) {
            visited += host
            val form =
                FormBody
                    .Builder()
                    .add("account", account)
                    .add("password", Md5.hex(password))
                    .add("featureCode", featureCodes.featureCode())
                    .build()
            val body = post(host, LOGIN_PATH, form, sessionId = null)
            val code = body.metaCode()
            if (code == META_OK) return parseLoginSession(body, host)
            if (code !in REGION_REDIRECT_CODES) throw loginFailure(code, body)
            val next =
                body.obj("loginArea")?.str("apiDomain") ?: serverError(code, "redirect without loginArea.apiDomain")
            host = CloudDomains.requireAllowed(next)
            if (visited.size > MAX_REDIRECTS) throw LoginError.RedirectLoop(visited + host)
        }
    }

    private fun loginFailure(
        code: Int?,
        body: JsonObject,
    ): LoginError = when (code) {
        in INVALID_CREDENTIAL_CODES -> LoginError.InvalidCredentials()
        META_CAPTCHA -> LoginError.NeedsVerification(VerificationKind.CAPTCHA, code)
        META_MFA_CODE -> LoginError.NeedsVerification(VerificationKind.SMS_CODE, code)
        META_TERMINAL_LIMIT -> LoginError.NeedsVerification(VerificationKind.TERMINAL_BINDING, code)
        else -> LoginError.Server(code, body.metaMessage())
    }

    private fun serverError(
        code: Int?,
        detail: String,
    ): Nothing = throw LoginError.Server(code, detail)

    /** Exchanges [session]'s refresh token for a fresh session on the same regional host. */
    public suspend fun refreshSession(session: Session): Session {
        val form =
            FormBody
                .Builder()
                .add("refreshSessionId", session.refreshSessionId)
                .add("featureCode", featureCodes.featureCode())
                .build()
        val request = Request.Builder().url(url(session.apiDomain, REFRESH_PATH)).put(form)
        val (status, body) = execute(request, REFRESH_PATH, sessionId = null)
        val code = body.metaCode()
        if (status != HTTP_OK || code != META_OK) throw LoginError.SessionExpired(code ?: status)
        val info = body.obj("sessionInfo") ?: serverError(code, "refresh without sessionInfo")
        return session.copy(
            sessionId = info.str("sessionId") ?: session.sessionId,
            refreshSessionId = info.str("refreshSessionId") ?: session.refreshSessionId,
        )
    }

    /** Invalidates [session] on the server. Errors are swallowed: a dead session is the goal anyway. */
    public suspend fun logout(session: Session) {
        val request = Request.Builder().url(url(session.apiDomain, LOGOUT_PATH)).delete()
        runCatching { execute(request, LOGOUT_PATH, session.sessionId) }
    }

    /**
     * Authenticated GET of [path] on the session's host with [query] parameters. Throws
     * [ApiException] on HTTP >= 400 or a `meta.code` other than 200.
     */
    public suspend fun get(
        session: Session,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): JsonObject {
        val url =
            url(session.apiDomain, path)
                .newBuilder()
                .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
        val request = Request.Builder().url(url).get()
        val (status, body) = execute(request, path, session.sessionId)
        val code = body.metaCode()
        if (status >= HTTP_BAD_REQUEST || (code != null && code != META_OK)) {
            throw ApiException(status, code, path, body.metaMessage())
        }
        return body
    }

    /**
     * Unauthenticated GET of an absolute [url] with the app headers plus [extraHeaders]. Used for
     * the VTDU token service, which lives on a different host and authenticates by query string.
     * Returns the raw status and parsed body without interpreting `meta.code`.
     */
    public suspend fun getRaw(
        url: HttpUrl,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<Int, JsonObject> {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
        return execute(request, url.encodedPath, sessionId = null)
    }

    private suspend fun post(
        host: String,
        path: String,
        form: FormBody,
        sessionId: String?,
    ): JsonObject {
        val request = Request.Builder().url(url(host, path)).post(form)
        return execute(request, path, sessionId).second
    }

    private fun url(
        host: String,
        path: String,
    ): HttpUrl = hosts
        .baseUrl(CloudDomains.requireAllowed(host))
        .newBuilder()
        .encodedPath(path)
        .build()

    private suspend fun execute(
        builder: Request.Builder,
        path: String,
        sessionId: String?,
    ): Pair<Int, JsonObject> {
        appHeaders().forEach { (k, v) -> builder.header(k, v) }
        if (sessionId != null) builder.header("sessionId", sessionId)
        val request = builder.build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    val body = if (text.isBlank()) JsonObject(emptyMap()) else CloudJson.parseObject(text)
                    response.code to body
                }
            } catch (e: IOException) {
                throw LoginError.Network(e)
            } catch (e: SerializationException) {
                throw LoginError.Network(IOException("$path: response is not JSON", e))
            }
        }
    }

    private fun appHeaders(): Map<String, String> = mapOf(
        "clientType" to CLIENT_TYPE,
        "lang" to LANG,
        "language" to LANG,
        "featureCode" to featureCodes.featureCode(),
        "netType" to "WIFI",
        "customno" to "1000001",
        "clientNo" to "web_site",
        "appId" to "ys7",
        "osVersion" to "",
        "clientVersion" to "",
        "ssid" to "",
        "User-Agent" to USER_AGENT,
    )

    private fun parseLoginSession(
        body: JsonObject,
        host: String,
    ): Session {
        val login = body.obj("loginSession") ?: serverError(META_OK, "login without loginSession")
        val sessionId = login.str("sessionId") ?: serverError(META_OK, "login without sessionId")
        val refresh = login.str("rfSessionId") ?: serverError(META_OK, "login without rfSessionId")
        val area = body.obj("loginArea")
        val user = body.obj("loginUser")
        // A 200 may still name the regional host; later calls to the global host would return 401.
        val apiDomain = area?.str("apiDomain")?.let(CloudDomains::requireAllowed) ?: host
        return Session(
            sessionId = sessionId,
            refreshSessionId = refresh,
            apiDomain = apiDomain,
            userId = user?.str("userId") ?: user?.str("userCode"),
            areaId = area?.int("areaId") ?: user?.int("areaId"),
        )
    }

    /** Protocol constants shared with tests. */
    public companion object {
        /** `clientType` of the Hik-Connect Android app; EZVIZ would be `1`. */
        public const val CLIENT_TYPE: String = "55"
        private const val LANG = "en-US"
        private const val USER_AGENT = "okhttp/3.12.1"
        private const val LOGIN_PATH = "/v3/users/login/v2"
        private const val REFRESH_PATH = "/v3/apigateway/login"
        private const val LOGOUT_PATH = "/v3/users/logout/v2"
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val META_OK = 200
        private const val META_CAPTCHA = 1015
        private const val META_MFA_CODE = 6002
        private const val META_TERMINAL_LIMIT = 1069
        private const val MAX_REDIRECTS = 1
        private val REGION_REDIRECT_CODES = setOf(1100, 101100)
        private val INVALID_CREDENTIAL_CODES = setOf(1013, 1014, 1226)
        private const val TIMEOUT_SECONDS = 25L

        /** OkHttp client with the 25 s timeouts cloud-cam-viewer found sufficient for the cloud. */
        public fun defaultHttpClient(): OkHttpClient = OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
