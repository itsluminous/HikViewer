package com.itsluminous.hikviewer.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode

/**
 * Where tiles sit in each grid layout, and which layout was used last.
 *
 * Each [GridLayoutMode] keeps its own ordered list of camera keys, so switching 4x3 → 1 + 5 → 4x3
 * brings the 4x3 arrangement back, and a 1 + N layout's position 0 is its hero. Cameras are
 * referenced by [com.itsluminous.hikviewer.core.devices.Camera.key] (`serial/channel`). The
 * store is a plain permutation; [com.itsluminous.hikviewer.ui.grid.GridArrangement] reconciles
 * it with the cameras actually visible. Shares the settings preferences so logout wipes it too.
 *
 * Installs that stored the single `HERO` layout of earlier versions are migrated on first use:
 * its arrangement (whose position 0 was the user's hero) seeds every 1 + N layout, and a last
 * layout of `HERO` becomes 1 + 5, the entry whose hero is 2x like the old one. The very first
 * version's `heroCamera` preference (a camera key chosen in Settings) is migrated the same way:
 * it becomes position 0 of every 1 + N layout that has no arrangement yet, then it is removed.
 * The Settings screen no longer has a hero entry; the hero is chosen on the grid.
 */
class GridArrangementStore(
    private val prefs: SharedPreferences,
) {
    init {
        migrateLegacyHero()
        migrateHeroCamera()
    }

    /** The layout shown last, restored on launch. */
    var lastLayout: GridLayoutMode
        get() = GridLayoutMode.fromStoredName(prefs.getString(KEY_LAST_LAYOUT, null)) ?: GridLayoutMode.AUTO
        set(value) = prefs.edit { putString(KEY_LAST_LAYOUT, value.name) }

    /** Stored camera-key order for [layout], or null when the user never moved a tile in it. */
    fun order(layout: GridLayoutMode): List<String>? = readOrder(KEY_ORDER_PREFIX + layout.name)

    /** Persists [keys] as the order for [layout]. */
    fun setOrder(
        layout: GridLayoutMode,
        keys: List<String>,
    ) = prefs.edit { putString(KEY_ORDER_PREFIX + layout.name, keys.joinToString(SEPARATOR)) }

    private fun readOrder(key: String): List<String>? =
        prefs.getString(key, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }

    private fun migrateLegacyHero() {
        val legacyOrderKey = KEY_ORDER_PREFIX + GridLayoutMode.LEGACY_HERO_NAME
        val legacyOrder = readOrder(legacyOrderKey)
        val legacyLast = prefs.getString(KEY_LAST_LAYOUT, null) == GridLayoutMode.LEGACY_HERO_NAME
        if (legacyOrder == null && !legacyLast) return
        prefs.edit {
            if (legacyOrder != null) {
                GridLayoutMode.heroModes.forEach { mode ->
                    val key = KEY_ORDER_PREFIX + mode.name
                    if (!prefs.contains(key)) putString(key, legacyOrder.joinToString(SEPARATOR))
                }
                remove(legacyOrderKey)
            }
            if (legacyLast) putString(KEY_LAST_LAYOUT, GridLayoutMode.HERO_1_5.name)
        }
    }

    /**
     * A stored list is a prefix: `GridArrangement.apply` puts its keys first and appends the
     * rest, so a one-key list makes that camera the hero and leaves the others in Settings order.
     */
    private fun migrateHeroCamera() {
        val hero = prefs.getString(KEY_LEGACY_HERO_CAMERA, null) ?: return
        prefs.edit {
            GridLayoutMode.heroModes.forEach { mode ->
                val key = KEY_ORDER_PREFIX + mode.name
                if (!prefs.contains(key)) putString(key, hero)
            }
            remove(KEY_LEGACY_HERO_CAMERA)
        }
    }

    private companion object {
        const val SEPARATOR = ","

        /** The first version's Settings → Hero camera; gone since the hero is position 0 of a 1 + N layout. */
        const val KEY_LEGACY_HERO_CAMERA = "heroCamera"

        /** Same key SettingsStore used for the chosen layout before arrangements existed, so upgrades keep it. */
        const val KEY_LAST_LAYOUT = "gridLayout"
        const val KEY_ORDER_PREFIX = "arrangement."
    }
}
