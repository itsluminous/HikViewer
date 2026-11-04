package com.itsluminous.hikviewer.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GridArrangementStoreTest {
    private val prefs =
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getSharedPreferences("t", 0)
            .also { it.edit().clear().commit() }
    private val store = GridArrangementStore(prefs)

    @Test
    fun `defaults to the auto layout with no arrangement anywhere`() {
        assertThat(store.lastLayout).isEqualTo(GridLayoutMode.AUTO)
        GridLayoutMode.entries.forEach { assertThat(store.order(it)).isNull() }
    }

    @Test
    fun `last layout round-trips and survives an unknown stored name`() {
        store.lastLayout = GridLayoutMode.HERO
        assertThat(GridArrangementStore(prefs).lastLayout).isEqualTo(GridLayoutMode.HERO)
        prefs.edit().putString("gridLayout", "SPIRAL").commit()
        assertThat(store.lastLayout).isEqualTo(GridLayoutMode.AUTO)
    }

    @Test
    fun `each layout keeps its own order`() {
        store.setOrder(GridLayoutMode.GRID_4X3, listOf("S/2", "S/1", "S/3"))
        store.setOrder(GridLayoutMode.HERO, listOf("S/3", "S/1", "S/2"))
        val again = GridArrangementStore(prefs)
        assertThat(again.order(GridLayoutMode.GRID_4X3)).containsExactly("S/2", "S/1", "S/3").inOrder()
        assertThat(again.order(GridLayoutMode.HERO)).containsExactly("S/3", "S/1", "S/2").inOrder()
        assertThat(again.order(GridLayoutMode.GRID_4X4)).isNull()
    }

    @Test
    fun `shares the settings preferences so a settings clear wipes it`() {
        store.setOrder(GridLayoutMode.AUTO, listOf("S/1"))
        store.lastLayout = GridLayoutMode.GRID_3X3
        SettingsStore(prefs).clear()
        assertThat(store.order(GridLayoutMode.AUTO)).isNull()
        assertThat(store.lastLayout).isEqualTo(GridLayoutMode.AUTO)
    }
}
