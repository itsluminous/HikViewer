package com.itsluminous.hikviewer.ui.grid

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.fakes.TestGraph
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraOrderTest {
    private val graph = TestGraph.create(ApplicationProvider.getApplicationContext())
    private val all =
        (1..4).map { Camera("S", it, "cam $it", hasSignal = it != 4, isOnline = true) }

    @Test
    fun `without an arrangement the settings order is the grid order`() {
        assertThat(CameraOrder.arranged(all, graph.settingsStore, graph.arrangementStore).map { it.channelNo })
            .containsExactly(1, 2, 3)
            .inOrder()
        graph.settingsStore.visibleCameraKeys = listOf("S/4", "S/2")
        assertThat(CameraOrder.arranged(all, graph.settingsStore, graph.arrangementStore).map { it.channelNo })
            .containsExactly(4, 2)
            .inOrder()
    }

    @Test
    fun `the last layout's arrangement reorders the visible cameras`() {
        graph.arrangementStore.lastLayout = GridLayoutMode.GRID_3X3
        graph.arrangementStore.setOrder(GridLayoutMode.GRID_3X3, listOf("S/3", "S/1"))
        graph.arrangementStore.setOrder(GridLayoutMode.AUTO, listOf("S/2"))
        assertThat(CameraOrder.arranged(all, graph.settingsStore, graph.arrangementStore).map { it.channelNo })
            .containsExactly(3, 1, 2)
            .inOrder()
        graph.arrangementStore.lastLayout = GridLayoutMode.AUTO
        assertThat(CameraOrder.arranged(all, graph.settingsStore, graph.arrangementStore).map { it.channelNo })
            .containsExactly(2, 1, 3)
            .inOrder()
    }
}
