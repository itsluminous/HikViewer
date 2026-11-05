package com.itsluminous.hikviewer.ui.grid

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.devices.Camera
import org.junit.Test

class GridArrangementTest {
    private fun cam(n: Int) = Camera("S", n, "Camera $n", hasSignal = true, isOnline = true)

    private val visible = (1..4).map(::cam)

    @Test
    fun `no stored order keeps the settings order`() {
        assertThat(GridArrangement.apply(visible, null)).isEqualTo(visible)
        assertThat(GridArrangement.apply(visible, emptyList())).isEqualTo(visible)
    }

    @Test
    fun `stored keys come first in their order`() {
        val out = GridArrangement.apply(visible, listOf("S/3", "S/1", "S/4", "S/2"))
        assertThat(out.map { it.channelNo }).containsExactly(3, 1, 4, 2).inOrder()
    }

    @Test
    fun `new cameras are appended in settings order, vanished ones dropped`() {
        val out = GridArrangement.apply(visible, listOf("S/4", "S/9", "S/2"))
        assertThat(out.map { it.channelNo }).containsExactly(4, 2, 1, 3).inOrder()
    }

    @Test
    fun `swap exchanges two positions and ignores bad indices`() {
        val keys = listOf("a", "b", "c", "d")
        assertThat(GridArrangement.swapped(keys, 0, 3)).containsExactly("d", "b", "c", "a").inOrder()
        assertThat(GridArrangement.swapped(keys, 2, 2)).isEqualTo(keys)
        assertThat(GridArrangement.swapped(keys, 1, 4)).isEqualTo(keys)
        assertThat(GridArrangement.swapped(keys, -1, 1)).isEqualTo(keys)
    }

    @Test
    fun `replaced swaps a key already on the grid and substitutes one that is not`() {
        val keys = listOf("a", "b", "c", "d")
        assertThat(GridArrangement.replaced(keys, 1, "d")).containsExactly("a", "d", "c", "b").inOrder()
        assertThat(GridArrangement.replaced(keys, 0, "c")).containsExactly("c", "b", "a", "d").inOrder()
        assertThat(GridArrangement.replaced(keys, 2, "x")).containsExactly("a", "b", "x", "d").inOrder()
        assertThat(GridArrangement.replaced(keys, 1, "b")).isEqualTo(keys)
        assertThat(GridArrangement.replaced(keys, 4, "a")).isEqualTo(keys)
        assertThat(GridArrangement.replaced(keys, -1, "x")).isEqualTo(keys)
    }
}
