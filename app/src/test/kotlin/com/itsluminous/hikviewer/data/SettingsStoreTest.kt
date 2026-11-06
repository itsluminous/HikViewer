package com.itsluminous.hikviewer.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.stream.StreamQuality
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private val prefs =
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getSharedPreferences("t", 0)
            .also { it.edit().clear().commit() }
    private val store = SettingsStore(prefs)

    @Test
    fun `defaults match the brief`() {
        assertThat(store.visibleCameraKeys).isNull()
        assertThat(store.fullscreenQuality).isEqualTo(StreamQuality.SUB)
        assertThat(store.verificationCode).isNull()
        assertThat(store.showCameraNames).isFalse()
        assertThat(store.showFrameRate).isFalse()
        assertThat(store.keepScreenOn).isFalse()
        assertThat(store.liveCameras).isNull() // Automatic
    }

    @Test
    fun `live cameras round-trips every offered count and null is Automatic again`() {
        assertThat(SettingsStore.LIVE_CAMERA_CHOICES).containsExactly(4, 6, 8, 10, 12, 14, 16).inOrder()
        SettingsStore.LIVE_CAMERA_CHOICES.forEach { n ->
            store.liveCameras = n
            assertThat(SettingsStore(prefs).liveCameras).isEqualTo(n)
        }
        store.liveCameras = null
        assertThat(SettingsStore(prefs).liveCameras).isNull()
        assertThat(prefs.contains("liveCameras")).isFalse()
    }

    @Test
    fun `a stored live-camera count that is not offered reads as Automatic`() {
        prefs.edit().putInt("liveCameras", 7).commit()
        assertThat(store.liveCameras).isNull()
        prefs.edit().putInt("liveCameras", 32).commit()
        assertThat(store.liveCameras).isNull()
    }

    @Test
    fun `keep screen on round-trips`() {
        store.keepScreenOn = true
        assertThat(SettingsStore(prefs).keepScreenOn).isTrue()
        store.keepScreenOn = false
        assertThat(SettingsStore(prefs).keepScreenOn).isFalse()
    }

    @Test
    fun `overlay toggles round-trip`() {
        store.showCameraNames = true
        store.showFrameRate = true
        val again = SettingsStore(prefs)
        assertThat(again.showCameraNames).isTrue()
        assertThat(again.showFrameRate).isTrue()
        again.showFrameRate = false
        assertThat(SettingsStore(prefs).showFrameRate).isFalse()
    }

    @Test
    fun `values round-trip`() {
        store.visibleCameraKeys = listOf("A/1", "A/3")
        store.fullscreenQuality = StreamQuality.MAIN
        store.verificationCode = "ABCDEF"
        val again = SettingsStore(prefs)
        assertThat(again.visibleCameraKeys).containsExactly("A/1", "A/3").inOrder()
        assertThat(again.fullscreenQuality).isEqualTo(StreamQuality.MAIN)
        assertThat(again.verificationCode).isEqualTo("ABCDEF")
    }

    @Test
    fun `empty visible list is a choice, null is no choice`() {
        store.visibleCameraKeys = emptyList()
        assertThat(store.visibleCameraKeys).isEmpty()
        store.visibleCameraKeys = null
        assertThat(store.visibleCameraKeys).isNull()
    }

    @Test
    fun `unknown stored enum names fall back to defaults`() {
        prefs
            .edit()
            .putString("fullscreenQuality", "4K")
            .commit()
        assertThat(store.fullscreenQuality).isEqualTo(StreamQuality.SUB)
    }

    @Test
    fun `clear wipes everything`() {
        store.verificationCode = "x"
        store.visibleCameraKeys = listOf("y")
        store.clear()
        assertThat(store.verificationCode).isNull()
        assertThat(store.visibleCameraKeys).isNull()
    }
}
