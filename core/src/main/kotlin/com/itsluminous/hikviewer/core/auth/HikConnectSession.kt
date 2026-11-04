package com.itsluminous.hikviewer.core.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Holds the current [Session] and renews it transparently.
 *
 * The cloud rejects a stale `sessionId` with 401; the fix is to call the refresh endpoint and repeat
 * the request, which every repository would otherwise have to implement. Doing it here, under a
 * mutex, also means concurrent stream opens do not all refresh at once (each refresh rotates the
 * refresh token, so a racing second refresh would be rejected). [onSessionChanged] lets `:app`
 * persist the rotated tokens so the next launch resumes instead of logging in again (repeated logins
 * are what make the cloud demand CAPTCHAs).
 */
public class HikConnectSession(
    /** The REST client used for all calls. */
    public val client: HikConnectClient,
    initial: Session,
    private val onSessionChanged: (Session) -> Unit = {},
) {
    private val mutex = Mutex()

    /** The session currently in use; replaced after each successful refresh. */
    public var current: Session = initial
        private set

    /** Authenticated GET with one refresh-and-retry on 401. */
    public suspend fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): JsonObject = withRetry { session -> client.get(session, path, query) }

    /**
     * Runs [block] with the current session; if it throws an unauthorized [ApiException], refreshes
     * once and runs it again. Any other failure propagates.
     */
    public suspend fun <T> withRetry(block: suspend (Session) -> T): T {
        val before = current
        return try {
            block(before)
        } catch (e: ApiException) {
            if (!e.isUnauthorized) throw e
            block(refreshIfStillCurrent(before))
        }
    }

    /** Forces a refresh now (for example after a VTDU 401) and returns the new session. */
    public suspend fun refresh(): Session = refreshIfStillCurrent(current)

    /** Logs out on the server; the object must not be used afterwards. */
    public suspend fun logout() {
        client.logout(current)
    }

    private suspend fun refreshIfStillCurrent(seen: Session): Session = mutex.withLock {
        // Another caller may have refreshed while we waited for the lock; reuse its result.
        if (current !== seen) return current
        val renewed = client.refreshSession(current)
        current = renewed
        onSessionChanged(renewed)
        renewed
    }
}
