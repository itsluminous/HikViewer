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
    fun `defaults to the auto layout in both orientations with no arrangement anywhere`() {
        assertThat(store.lastLayout).isEqualTo(GridLayoutMode.AUTO)
        assertThat(store.lastLayoutPortrait).isEqualTo(GridLayoutMode.AUTO)
        assertThat(store.layout(portrait = false)).isEqualTo(GridLayoutMode.AUTO)
        assertThat(store.layout(portrait = true)).isEqualTo(GridLayoutMode.AUTO)
        GridLayoutMode.entries.forEach { assertThat(store.order(it)).isNull() }
    }

    /** A phone keeps a layout per orientation: 2 x 7 upright and 4 x 3 sideways are two keys that never touch. */
    @Test
    fun `the landscape and portrait layouts are two keys, each read and written by its flag`() {
        store.setLayout(portrait = true, GridLayoutMode.GRID_2X7)
        assertThat(store.lastLayoutPortrait).isEqualTo(GridLayoutMode.GRID_2X7)
        assertThat(store.lastLayout).isEqualTo(GridLayoutMode.AUTO)
        store.setLayout(portrait = false, GridLayoutMode.GRID_4X3)
        assertThat(store.lastLayout).isEqualTo(GridLayoutMode.GRID_4X3)
        assertThat(store.lastLayoutPortrait).isEqualTo(GridLayoutMode.GRID_2X7)
        assertThat(prefs.getString("gridLayout", null)).isEqualTo("GRID_4X3")
        assertThat(prefs.getString("gridLayoutPortrait", null)).isEqualTo("GRID_2X7")
        val again = GridArrangementStore(prefs)
        assertThat(again.layout(portrait = false)).isEqualTo(GridLayoutMode.GRID_4X3)
        assertThat(again.layout(portrait = true)).isEqualTo(GridLayoutMode.GRID_2X7)
        // Each layout's arrangement is its own, whichever orientation it was moved in.
        again.setOrder(GridLayoutMode.GRID_2X7, listOf("S/2", "S/1"))
        again.setOrder(GridLayoutMode.GRID_4X3, listOf("S/3", "S/1"))
        assertThat(again.order(GridLayoutMode.GRID_2X7)).containsExactly("S/2", "S/1").inOrder()
        assertThat(again.order(GridLayoutMode.GRID_4X3)).containsExactly("S/3", "S/1").inOrder()
        prefs.edit().putString("gridLayoutPortrait", "SPIRAL").commit()
        assertThat(again.lastLayoutPortrait).isEqualTo(GridLayoutMode.AUTO)
    }

    /**
     * Before 0.3 one key served both orientations. A landscape layout under it (every TV, and a
     * phone that last chose sideways) stays the landscape layout and portrait starts at AUTO.
     */
    @Test
    fun `a stored landscape layout stays the landscape layout and portrait starts at auto`() {
        listOf(GridLayoutMode.GRID_4X3, GridLayoutMode.HERO_1_7, GridLayoutMode.AUTO).forEach { stored ->
            prefs
                .edit()
                .clear()
                .putString("gridLayout", stored.name)
                .commit()
            val migrated = GridArrangementStore(prefs)
            assertThat(migrated.lastLayout).isEqualTo(stored)
            assertThat(migrated.lastLayoutPortrait).isEqualTo(GridLayoutMode.AUTO)
            assertThat(prefs.contains("gridLayoutPortrait")).isFalse()
        }
    }

    /** A portrait layout under the old key was chosen upright: it moves to the portrait key, sideways goes back to AUTO. */
    @Test
    fun `a stored portrait layout moves to the portrait key once`() {
        listOf(GridLayoutMode.GRID_2X3, GridLayoutMode.GRID_1X4, GridLayoutMode.HERO_1_6).forEach { stored ->
            prefs
                .edit()
                .clear()
                .putString("gridLayout", stored.name)
                .commit()
            val migrated = GridArrangementStore(prefs)
            assertThat(migrated.lastLayoutPortrait).isEqualTo(stored)
            assertThat(migrated.lastLayout).isEqualTo(GridLayoutMode.AUTO)
            assertThat(prefs.getString("gridLayout", null)).isEqualTo("AUTO")
        }
        // Once both keys exist nothing moves again: a later sideways pick is kept as it was made.
        GridArrangementStore(prefs).lastLayout = GridLayoutMode.GRID_4X3
        val again = GridArrangementStore(prefs)
        assertThat(again.lastLayout).isEqualTo(GridLayoutMode.GRID_4X3)
        assertThat(again.lastLayoutPortrait).isEqualTo(GridLayoutMode.HERO_1_6)
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

    /** Every layout's arrangement lives under the one key named after the entry, the portrait shapes included. */
    @Test
    fun `a portrait shape's arrangement has one key like every other layout's`() {
        GridLayoutMode.entries.filter { it.isFixed }.forEach { mode ->
            store.setOrder(mode, listOf("S/${mode.ordinal}", "S/1"))
            assertThat(prefs.getString("arrangement.${mode.name}", null)).isEqualTo("S/${mode.ordinal},S/1")
        }
        assertThat(prefs.all.keys.filter { it.startsWith("arrangement.") })
            .containsExactlyElementsIn(GridLayoutMode.entries.filter { it.isFixed }.map { "arrangement.${it.name}" })
        store.lastLayoutPortrait = GridLayoutMode.GRID_2X3
        assertThat(prefs.getString("gridLayoutPortrait", null)).isEqualTo("GRID_2X3")
        assertThat(GridArrangementStore(prefs).lastLayoutPortrait).isEqualTo(GridLayoutMode.GRID_2X3)
    }

    @Test
    fun `shares the settings preferences so a settings clear wipes it`() {
        store.setOrder(GridLayoutMode.AUTO, listOf("S/1"))
        store.lastLayout = GridLayoutMode.GRID_3X3
        store.lastLayoutPortrait = GridLayoutMode.GRID_2X3
        SettingsStore(prefs).clear()
        assertThat(store.order(GridLayoutMode.AUTO)).isNull()
        assertThat(store.lastLayout).isEqualTo(GridLayoutMode.AUTO)
        assertThat(store.lastLayoutPortrait).isEqualTo(GridLayoutMode.AUTO)
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
        prefs
            .edit()
            .putString("heroCamera", "S/7")
            .putString("arrangement.HERO_1_7", "S/2,S/1")
            .commit()
        val migrated = GridArrangementStore(prefs)
        assertThat(migrated.order(GridLayoutMode.HERO_1_5)).containsExactly("S/7")
        assertThat(migrated.order(GridLayoutMode.HERO_1_7)).containsExactly("S/2", "S/1").inOrder()
        assertThat(prefs.contains("heroCamera")).isFalse()
        assertThat(migrated.order(GridLayoutMode.GRID_4X3)).isNull()
    }
}
