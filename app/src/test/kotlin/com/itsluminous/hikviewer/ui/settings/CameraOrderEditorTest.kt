package com.itsluminous.hikviewer.ui.settings

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.devices.Camera
import org.junit.Test

class CameraOrderEditorTest {
    private val cams =
        (1..5).map { Camera("S", it, "cam$it", hasSignal = it != 4, isOnline = true) }

    @Test
    fun `without a choice signal cameras are ticked first in channel order`() {
        val editor = CameraOrderEditor(cams, null)
        assertThat(editor.items.map { it.camera.channelNo }).containsExactly(1, 2, 3, 5, 4).inOrder()
        assertThat(editor.items.map { it.visible }).containsExactly(true, true, true, true, false).inOrder()
        assertThat(editor.visibleKeys()).containsExactly("S/1", "S/2", "S/3", "S/5").inOrder()
    }

    @Test
    fun `saved order comes first, the rest unticked`() {
        val editor = CameraOrderEditor(cams, listOf("S/4", "S/2"))
        assertThat(editor.items.map { it.camera.channelNo }).containsExactly(4, 2, 1, 3, 5).inOrder()
        assertThat(editor.visibleKeys()).containsExactly("S/4", "S/2").inOrder()
    }

    @Test
    fun `toggle visible`() {
        val editor = CameraOrderEditor(cams, listOf("S/1"))
        editor.toggleVisible(0)
        editor.toggleVisible(2)
        assertThat(editor.visibleKeys()).containsExactly("S/3")
    }

    @Test
    fun `pick up, move, drop`() {
        val editor = CameraOrderEditor(cams, null)
        assertThat(editor.moveBy(1)).isNull()
        assertThat(editor.togglePickUp(0)).isTrue()
        assertThat(editor.pickedUp).isEqualTo(0)
        assertThat(editor.moveBy(-1)).isNull()
        assertThat(editor.moveBy(1)).isEqualTo(1)
        assertThat(editor.moveBy(1)).isEqualTo(2)
        assertThat(editor.items.map { it.camera.channelNo }).containsExactly(2, 3, 1, 5, 4).inOrder()
        assertThat(editor.togglePickUp(2)).isFalse()
        assertThat(editor.pickedUp).isNull()
        assertThat(editor.visibleKeys()).containsExactly("S/2", "S/3", "S/1", "S/5").inOrder()
    }

    @Test
    fun `moving past the end is a no-op`() {
        val editor = CameraOrderEditor(cams, null)
        editor.togglePickUp(4)
        assertThat(editor.moveBy(1)).isNull()
        assertThat(editor.pickedUp).isEqualTo(4)
    }

    @Test
    fun `picking up another row switches the picked row`() {
        val editor = CameraOrderEditor(cams, null)
        editor.togglePickUp(1)
        assertThat(editor.togglePickUp(3)).isTrue()
        assertThat(editor.pickedUp).isEqualTo(3)
    }
}
