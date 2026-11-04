package com.itsluminous.hikviewer.ui.grid

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.devices.Camera
import org.junit.Test

class VisibleCamerasTest {
    private val cams =
        listOf(
            Camera("S", 1, "a", hasSignal = true, isOnline = true),
            Camera("S", 2, "b", hasSignal = false, isOnline = true),
            Camera("S", 3, "c", hasSignal = true, isOnline = true),
        )

    @Test
    fun `default is cameras with signal in channel order`() {
        assertThat(VisibleCameras.resolve(cams, null).map { it.channelNo }).containsExactly(1, 3).inOrder()
    }

    @Test
    fun `chosen keys win, in their order, unknown keys dropped`() {
        val visible = VisibleCameras.resolve(cams, listOf("S/3", "S/9", "S/2"))
        assertThat(visible.map { it.channelNo }).containsExactly(3, 2).inOrder()
        assertThat(VisibleCameras.resolve(cams, emptyList())).isEmpty()
    }

    @Test
    fun `hero index falls back to the first tile`() {
        val visible = VisibleCameras.resolve(cams, null)
        assertThat(VisibleCameras.heroIndex(visible, "S/3")).isEqualTo(1)
        assertThat(VisibleCameras.heroIndex(visible, "S/2")).isEqualTo(0)
        assertThat(VisibleCameras.heroIndex(visible, null)).isEqualTo(0)
        assertThat(VisibleCameras.heroIndex(emptyList(), "S/1")).isNull()
    }
}
