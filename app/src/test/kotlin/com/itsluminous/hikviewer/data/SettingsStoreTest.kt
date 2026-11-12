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
        assertThat(store.cameraNamePosition).isEqualTo(NamePosition.BOTTOM_LEFT)
        assertThat(NamePosition.DEFAULT).isEqualTo(NamePosition.BOTTOM_LEFT)
    }

    @Test
    fun `recorder offset is remembered per serial, including zero and negative, and cleared on logout`() {
        assertThat(store.recorderOffsetMinutes("DVR1")).isNull()
        store.setRecorderOffsetMinutes("DVR1", 810)
        store.setRecorderOffsetMinutes("DVR2", -225)
        store.setRecorderOffsetMinutes("DVR3", 0)
        val reopened = SettingsStore(prefs)
        assertThat(reopened.recorderOffsetMinutes("DVR1")).isEqualTo(810)
        assertThat(reopened.recorderOffsetMinutes("DVR2")).isEqualTo(-225)
        assertThat(reopened.recorderOffsetMinutes("DVR3")).isEqualTo(0)
        assertThat(reopened.recorderOffsetMinutes("DVR4")).isNull()
        store.setRecorderOffsetMinutes("DVR1", null)
        assertThat(store.recorderOffsetMinutes("DVR1")).isNull()
        // A learnt value, not a choice: backups never carry it and a restore never touches it.
        store.applyChoices(store.choices())
        assertThat(store.recorderOffsetMinutes("DVR2")).isEqualTo(-225)
        store.clear()
        assertThat(store.recorderOffsetMinutes("DVR2")).isNull()
    }

    @Test
    fun `camera name position round-trips and an unknown stored name is the default`() {
        store.cameraNamePosition = NamePosition.TOP_LEFT
        assertThat(SettingsStore(prefs).cameraNamePosition).isEqualTo(NamePosition.TOP_LEFT)
        store.cameraNamePosition = NamePosition.BOTTOM_RIGHT
        assertThat(SettingsStore(prefs).cameraNamePosition).isEqualTo(NamePosition.BOTTOM_RIGHT)
        prefs.edit().putString("cameraNamePosition", "MIDDLE").commit()
        assertThat(store.cameraNamePosition).isEqualTo(NamePosition.BOTTOM_LEFT)
        assertThat(NamePosition.fromName(null)).isEqualTo(NamePosition.BOTTOM_LEFT)
    }

    @Test
    fun `choices carry the name position and applyChoices writes it`() {
        store.cameraNamePosition = NamePosition.TOP_LEFT
        assertThat(store.choices().cameraNamePosition).isEqualTo(NamePosition.TOP_LEFT)
        store.applyChoices(store.choices().copy(cameraNamePosition = NamePosition.BOTTOM_RIGHT))
        assertThat(SettingsStore(prefs).cameraNamePosition).isEqualTo(NamePosition.BOTTOM_RIGHT)
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
