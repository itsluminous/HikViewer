package com.itsluminous.hikviewer.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
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
        assertThat(store.gridLayout).isEqualTo(GridLayoutMode.AUTO)
        assertThat(store.visibleCameraKeys).isNull()
        assertThat(store.heroCameraKey).isNull()
        assertThat(store.fullscreenQuality).isEqualTo(StreamQuality.SUB)
        assertThat(store.verificationCode).isNull()
        assertThat(store.showCameraNames).isFalse()
        assertThat(store.showFrameRate).isFalse()
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
        store.gridLayout = GridLayoutMode.HERO
        store.visibleCameraKeys = listOf("A/1", "A/3")
        store.heroCameraKey = "A/3"
        store.fullscreenQuality = StreamQuality.MAIN
        store.verificationCode = "ABCDEF"
        val again = SettingsStore(prefs)
        assertThat(again.gridLayout).isEqualTo(GridLayoutMode.HERO)
        assertThat(again.visibleCameraKeys).containsExactly("A/1", "A/3").inOrder()
        assertThat(again.heroCameraKey).isEqualTo("A/3")
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
            .putString("gridLayout", "SPIRAL")
            .putString("fullscreenQuality", "4K")
            .commit()
        assertThat(store.gridLayout).isEqualTo(GridLayoutMode.AUTO)
        assertThat(store.fullscreenQuality).isEqualTo(StreamQuality.SUB)
    }

    @Test
    fun `clear wipes everything`() {
        store.verificationCode = "x"
        store.heroCameraKey = "y"
        store.clear()
        assertThat(store.verificationCode).isNull()
        assertThat(store.heroCameraKey).isNull()
    }
}
