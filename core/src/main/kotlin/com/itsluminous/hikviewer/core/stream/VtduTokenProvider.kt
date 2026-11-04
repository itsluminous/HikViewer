package com.itsluminous.hikviewer.core.stream

import com.itsluminous.hikviewer.core.CloudDomains
import com.itsluminous.hikviewer.core.auth.HikConnectSession
import com.itsluminous.hikviewer.core.auth.HostResolver
import com.itsluminous.hikviewer.core.json.CloudJson
import com.itsluminous.hikviewer.core.json.arr
import com.itsluminous.hikviewer.core.json.obj
import com.itsluminous.hikviewer.core.json.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * Fetches and caches the VTDU token that authorises a relay stream.
 *
 * The token service (`<region>auth.ezvizlife.com/vtdutoken2`) is not the API host: it is derived
 * from the API host (`apiiindia.` → `indiaauth.`) and, when that pattern does not apply, read from
 * `systemConfigInfo.authAddr`. It authenticates by query string: `ssid` is the session JWT and
 * `sign` is the JWT's `s` claim. A 401 means the session is stale: refresh and retry. Tokens are
 * account-wide and valid for a long time, so one fetch serves every tile; the reference client
 * measured 0.98 s per fetch, which would otherwise be paid once per stream.
 */
public class VtduTokenProvider(
    private val session: HikConnectSession,
    private val hosts: HostResolver = HostResolver.HTTPS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private val mutex = Mutex()
    private var cached: List<String> = emptyList()
    private var fetchedAt = 0L

    /** The first usable token, fetching when the cache is empty or older than the TTL. */
    public suspend fun token(): String = mutex.withLock {
        if (cached.isEmpty() || clock() - fetchedAt >= ttlMillis) {
            cached = fetch()
            fetchedAt = clock()
        }
        cached.first()
    }

    /** Drops the cache so the next [token] call fetches again (after a relay rejected the token). */
    public suspend fun invalidate(): Unit = mutex.withLock {
        cached = emptyList()
    }

    private suspend fun fetch(): List<String> {
        var attempt = 0
        while (true) {
            val current = session.current
            val url =
                hosts
                    .baseUrl(authHost(current.apiDomain))
                    .newBuilder()
                    .encodedPath(TOKEN_PATH)
                    .addQueryParameter("ssid", current.sessionId)
                    .addQueryParameter("sign", sessionSign(current.sessionId))
                    .build()
            val (status, body) = session.client.getRaw(url)
            if (status == HTTP_UNAUTHORIZED && attempt < MAX_RETRIES) {
                attempt++
                session.refresh()
                continue
            }
            if (status >= HTTP_BAD_REQUEST) throw VtmException("Could not get VTDU token: HTTP $status")
            val retcode = (body["retcode"] as? JsonPrimitive)?.content
            if (retcode != "0") throw VtmException("Could not get VTDU token: retcode $retcode")
            val tokens = body.arr("tokens")?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotEmpty) }
            if (tokens.isNullOrEmpty()) throw VtmException("Could not get VTDU token: empty token list")
            return tokens
        }
    }

    private suspend fun authHost(apiDomain: String): String {
        derivedAuthHost(apiDomain)?.let { return it }
        val info = session.get(SYSTEM_INFO_PATH).obj("systemConfigInfo") ?: JsonObject(emptyMap())
        val addr = info.str("authAddr")?.trim().orEmpty()
        val host = addr.removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (host.isEmpty() || host.equals("null", ignoreCase = true) || host.equals("none", ignoreCase = true)) {
            throw VtmException("Missing authAddr in service URLs")
        }
        return CloudDomains.requireAllowed(host)
    }

    /** Companion helpers, exposed for tests. */
    public companion object {
        private const val TOKEN_PATH = "/vtdutoken2"
        private const val SYSTEM_INFO_PATH = "/v3/configurations/system/info"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_BAD_REQUEST = 400
        private const val MAX_RETRIES = 3
        private const val DEFAULT_TTL_MILLIS = 60L * 60 * 1000
        private val apiHostPattern = Regex("^apii([a-z]+)\\.")

        /** `apiiindia.hik-connect.com` → `indiaauth.ezvizlife.com`; null when the host has no region part. */
        public fun derivedAuthHost(apiDomain: String): String? =
            apiHostPattern.find(apiDomain.trim().lowercase())?.let { "${it.groupValues[1]}auth.ezvizlife.com" }

        /** The `sign` query parameter: the `s` claim of the session JWT. */
        public fun sessionSign(sessionId: String): String {
            val parts = sessionId.split('.')
            if (parts.size < 2) throw VtmException("Current session token is not a JWT")
            val claims =
                try {
                    CloudJson.parseObject(Base64.getUrlDecoder().decode(padBase64(parts[1])).toString(Charsets.UTF_8))
                } catch (e: IllegalArgumentException) {
                    throw VtmException("Could not decode current session token claims", e)
                } catch (e: kotlinx.serialization.SerializationException) {
                    throw VtmException("Could not decode current session token claims", e)
                }
            return claims.str("s")?.takeIf(String::isNotEmpty)
                ?: throw VtmException("Current session token does not contain VTDU sign claim")
        }

        private fun padBase64(s: String): String = s + "=".repeat((4 - s.length % 4) % 4)
    }
}
