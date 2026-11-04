package com.itsluminous.hikviewer.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.Session
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    private val prefs =
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getSharedPreferences("s", 0)
            .also { it.edit().clear().commit() }
    private val store = SessionStore(prefs)

    @Test
    fun `feature code is generated once and is lowercase hex`() {
        val first = store.featureCode()
        assertThat(first).matches("[0-9a-f]{32}")
        assertThat(SessionStore(prefs).featureCode()).isEqualTo(first)
    }

    @Test
    fun `session round-trips and clears without touching the feature code`() {
        assertThat(store.session()).isNull()
        val code = store.featureCode()
        val session = Session("sid", "rid", "apiiindia.hik-connect.com", "u1", 244)
        store.save(session)
        assertThat(store.session()).isEqualTo(session)
        store.save(session.copy(userId = null, areaId = null))
        assertThat(store.session()).isEqualTo(session.copy(userId = null, areaId = null))
        store.clearSession()
        assertThat(store.session()).isNull()
        assertThat(store.featureCode()).isEqualTo(code)
    }

    @Test
    fun `partial session is treated as absent`() {
        prefs.edit().putString("sessionId", "x").commit()
        assertThat(store.session()).isNull()
    }
}
