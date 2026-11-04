package com.itsluminous.hikviewer.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.LoginError
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.auth.Session
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val sessionPrefs = context.getSharedPreferences("as", 0).also { it.edit().clear().commit() }
    private val settingsPrefs = context.getSharedPreferences("at", 0).also { it.edit().clear().commit() }
    private val cloud = FakeCloudServices()
    private val sessionStore = SessionStore(sessionPrefs)
    private val settingsStore = SettingsStore(settingsPrefs)

    private fun repo() = AccountRepository(cloud, sessionStore, settingsStore)

    @Test
    fun `starts logged out, login stores the session as typed`() = runTest {
        val repo = repo()
        assertThat(repo.isLoggedIn).isFalse()
        repo.login("911234567890", "secret", Region.INDIA)
        assertThat(repo.isLoggedIn).isTrue()
        assertThat(cloud.logins.single()).isEqualTo(Triple("911234567890", "secret", Region.INDIA))
        assertThat(sessionStore.session()?.sessionId).isEqualTo("sid-911234567890")
    }

    @Test
    fun `login failure propagates and leaves no session`() = runTest {
        val repo = repo()
        assertThrows(LoginError.InvalidCredentials::class.java) {
            kotlinx.coroutines.runBlocking { repo.login("123", "wrong", Region.INDIA) }
        }
        assertThat(repo.isLoggedIn).isFalse()
        assertThat(sessionStore.session()).isNull()
    }

    @Test
    fun `stored session is bound at construction and token refreshes are persisted`() = runTest {
        sessionStore.save(Session("sid", "rid", "apiiindia.hik-connect.com", null, null))
        val repo = repo()
        assertThat(repo.isLoggedIn).isTrue()
        val inventory = repo.loadInventory()
        assertThat(inventory.devices).hasSize(1)
        assertThat(inventory.cameras).hasSize(16)
        assertThat(inventory.needsVerificationCode).isTrue()
        cloud.accounts.single().onSessionChanged(Session("sid2", "rid2", "apiiindia.hik-connect.com", null, null))
        assertThat(sessionStore.session()?.sessionId).isEqualTo("sid2")
    }

    @Test
    fun `inventory is cached until refresh is requested`() = runTest {
        val repo = repo()
        repo.login("a", "secret", Region.INDIA)
        val first = repo.loadInventory()
        assertThat(repo.loadInventory()).isSameInstanceAs(first)
        assertThat(repo.inventory.first()).isSameInstanceAs(first)
        assertThat(repo.loadInventory(refresh = true)).isNotSameInstanceAs(first)
    }

    @Test
    fun `openStream passes the stored verification code and quality`() = runTest {
        val repo = repo()
        repo.login("a", "secret", Region.INDIA)
        settingsStore.verificationCode = "ABCDEF"
        val camera = repo.loadInventory().cameras.first()
        repo.openStream(camera, StreamQuality.MAIN)
        assertThat(
            cloud.accounts
                .single()
                .opened
                .single(),
        ).isEqualTo(camera to StreamQuality.MAIN)
    }

    @Test
    fun `logout clears session, settings and cache`() = runTest {
        val repo = repo()
        repo.login("a", "secret", Region.INDIA)
        repo.loadInventory()
        settingsStore.verificationCode = "ABCDEF"
        repo.logout()
        assertThat(cloud.logouts).isEqualTo(1)
        assertThat(repo.isLoggedIn).isFalse()
        assertThat(sessionStore.session()).isNull()
        assertThat(settingsStore.verificationCode).isNull()
        assertThat(repo.inventory.value).isNull()
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repo.loadInventory() }
        }
    }

    @Test
    fun `inventory helpers`() = runTest {
        val repo = repo()
        repo.login("a", "secret", Region.INDIA)
        val inv = repo.loadInventory()
        val cam = inv.camera("${FakeCloudServices.SERIAL}/3")
        assertThat(cam?.channelNo).isEqualTo(3)
        assertThat(inv.deviceOf(cam!!)?.serial).isEqualTo(FakeCloudServices.SERIAL)
        assertThat(inv.encryptPwd).isEqualTo(FakeCloudServices.GOOD_CODE_DOUBLE_MD5)
        assertThat(inv.camera("nope")).isNull()
    }
}
