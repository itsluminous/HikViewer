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
        store.lastLayout = GridLayoutMode.HERO_1_9
        assertThat(GridArrangementStore(prefs).lastLayout).isEqualTo(GridLayoutMode.HERO_1_9)
        prefs.edit().putString("gridLayout", "SPIRAL").commit()
        assertThat(store.lastLayout).isEqualTo(GridLayoutMode.AUTO)
    }

    @Test
    fun `each layout keeps its own order`() {
        store.setOrder(GridLayoutMode.GRID_4X3, listOf("S/2", "S/1", "S/3"))
        store.setOrder(GridLayoutMode.HERO_1_5, listOf("S/3", "S/1", "S/2"))
        val again = GridArrangementStore(prefs)
        assertThat(again.order(GridLayoutMode.GRID_4X3)).containsExactly("S/2", "S/1", "S/3").inOrder()
        assertThat(again.order(GridLayoutMode.HERO_1_5)).containsExactly("S/3", "S/1", "S/2").inOrder()
        assertThat(again.order(GridLayoutMode.HERO_1_7)).isNull()
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

    @Test
    fun `legacy single hero layout migrates to 1+5 and seeds every 1+N arrangement`() {
        prefs
            .edit()
            .clear()
            .putString("gridLayout", "HERO")
            .putString("arrangement.HERO", "S/4,S/1,S/2")
            .commit()
        val migrated = GridArrangementStore(prefs)
        assertThat(migrated.lastLayout).isEqualTo(GridLayoutMode.HERO_1_5)
        GridLayoutMode.heroModes.forEach { mode ->
            assertThat(migrated.order(mode)).containsExactly("S/4", "S/1", "S/2").inOrder()
        }
        assertThat(prefs.contains("arrangement.HERO")).isFalse()
        assertThat(prefs.getString("gridLayout", null)).isEqualTo("HERO_1_5")
        // A second construction is a no-op: nothing legacy is left.
        GridArrangementStore(prefs)
        assertThat(migrated.order(GridLayoutMode.HERO_1_5)).containsExactly("S/4", "S/1", "S/2").inOrder()
    }

    @Test
    fun `migration keeps a 1+N arrangement that already exists`() {
        prefs
            .edit()
            .clear()
            .putString("arrangement.HERO", "S/4,S/1")
            .putString("arrangement.HERO_1_7", "S/2,S/1")
            .putString("gridLayout", "GRID_4X3")
            .commit()
        val migrated = GridArrangementStore(prefs)
        assertThat(migrated.order(GridLayoutMode.HERO_1_7)).containsExactly("S/2", "S/1").inOrder()
        assertThat(migrated.order(GridLayoutMode.HERO_1_5)).containsExactly("S/4", "S/1").inOrder()
        assertThat(migrated.lastLayout).isEqualTo(GridLayoutMode.GRID_4X3)
    }

    @Test
    fun `the first version's hero camera becomes position 0 of the 1+N layouts without an arrangement`() {
        prefs.edit().putString("heroCamera", "S/7").putString("arrangement.HERO_1_7", "S/2,S/1").commit()
        val migrated = GridArrangementStore(prefs)
        assertThat(migrated.order(GridLayoutMode.HERO_1_5)).containsExactly("S/7")
        assertThat(migrated.order(GridLayoutMode.HERO_1_7)).containsExactly("S/2", "S/1").inOrder()
        assertThat(prefs.contains("heroCamera")).isFalse()
        assertThat(migrated.order(GridLayoutMode.GRID_4X3)).isNull()
    }
}
