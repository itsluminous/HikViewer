package com.itsluminous.hikviewer.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.itsluminous.hikviewer.core.auth.Session
import java.util.UUID

/**
 * Persists the Hik-Connect [Session] and the per-install feature code.
 *
 * The feature code is Hik-Connect's "terminal id"; it is generated once (UUID without dashes, so
 * it is lowercase hex like the official app's) and never changes, otherwise every launch looks
 * like a new device and the cloud starts asking for CAPTCHAs. The backing preferences are
 * encrypted in production because the session token grants full account access.
 */
class SessionStore(
    private val prefs: SharedPreferences,
) {
    /** The stable feature code, created on first access. */
    fun featureCode(): String {
        prefs.getString(KEY_FEATURE_CODE, null)?.let { return it }
        val fresh = UUID.randomUUID().toString().replace("-", "")
        prefs.edit { putString(KEY_FEATURE_CODE, fresh) }
        return fresh
    }

    /** The stored session, or null when nobody is logged in. */
    fun session(): Session? {
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH_ID, null) ?: return null
        val domain = prefs.getString(KEY_API_DOMAIN, null) ?: return null
        return Session(
            sessionId = sessionId,
            refreshSessionId = refresh,
            apiDomain = domain,
            userId = prefs.getString(KEY_USER_ID, null),
            areaId = if (prefs.contains(KEY_AREA_ID)) prefs.getInt(KEY_AREA_ID, 0) else null,
        )
    }

    /** Replaces the stored session (also called after every token refresh). */
    fun save(session: Session) {
        prefs.edit {
            putString(KEY_SESSION_ID, session.sessionId)
            putString(KEY_REFRESH_ID, session.refreshSessionId)
            putString(KEY_API_DOMAIN, session.apiDomain)
            putString(KEY_USER_ID, session.userId)
            val areaId = session.areaId
            if (areaId != null) putInt(KEY_AREA_ID, areaId) else remove(KEY_AREA_ID)
        }
    }

    /** Forgets the session but keeps the feature code (the install is still the same terminal). */
    fun clearSession() {
        prefs.edit {
            remove(KEY_SESSION_ID)
            remove(KEY_REFRESH_ID)
            remove(KEY_API_DOMAIN)
            remove(KEY_USER_ID)
            remove(KEY_AREA_ID)
        }
    }

    private companion object {
        const val KEY_FEATURE_CODE = "featureCode"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_REFRESH_ID = "refreshSessionId"
        const val KEY_API_DOMAIN = "apiDomain"
        const val KEY_USER_ID = "userId"
        const val KEY_AREA_ID = "areaId"
    }
}
