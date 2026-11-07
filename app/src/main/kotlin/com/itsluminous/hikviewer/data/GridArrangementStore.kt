package com.itsluminous.hikviewer.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode

/**
 * Where tiles sit in each grid layout, and which layout was used last in each orientation.
 *
 * Each [GridLayoutMode] keeps its own ordered list of camera keys, so switching 4x3 → 1 + 5 → 4x3
 * brings the 4x3 arrangement back, and a 1 + N layout's position 0 is its hero. Cameras are
 * referenced by [com.itsluminous.hikviewer.core.devices.Camera.key] (`serial/channel`). The
 * store is a plain permutation; [com.itsluminous.hikviewer.ui.grid.GridArrangement] reconciles
 * it with the cameras actually visible. Shares the settings preferences so logout wipes it too.
 *
 * The last layout is kept per orientation: [lastLayout] for a TV and a phone held sideways,
 * [lastLayoutPortrait] for a phone held upright, each picked from its own menu, so the user can
 * have 2 x 7 upright and 4 x 3 sideways and turning the phone switches between the two. Both
 * default to AUTO. An arrangement belongs to a layout, not an orientation: a camera moved in
 * 2 x 7 stays there whichever way the phone is held, and 4 x 3 keeps its own order.
 *
 * Before 0.3 there was one key for both orientations. A portrait layout found under it (a
 * portrait shape or an even hero, which only the upright menu offers) was chosen upright and
 * moves to the portrait key, leaving the sideways layout at AUTO; a landscape layout stays where
 * it is and portrait starts at AUTO.
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
        migratePortraitLayout()
    }

    /** The layout shown last on a TV or a phone held sideways, restored on launch. */
    var lastLayout: GridLayoutMode
        get() = readLayout(KEY_LAST_LAYOUT)
        set(value) = prefs.edit { putString(KEY_LAST_LAYOUT, value.name) }

    /** The layout shown last on a phone held upright, restored when it is upright again. */
    var lastLayoutPortrait: GridLayoutMode
        get() = readLayout(KEY_LAST_LAYOUT_PORTRAIT)
        set(value) = prefs.edit { putString(KEY_LAST_LAYOUT_PORTRAIT, value.name) }

    /** The last layout of the orientation that is [portrait] or not: one of the two properties above. */
    fun layout(portrait: Boolean): GridLayoutMode = if (portrait) lastLayoutPortrait else lastLayout

    /** Persists [mode] as the last layout of the orientation that is [portrait] or not. */
    fun setLayout(
        portrait: Boolean,
        mode: GridLayoutMode,
    ) {
        if (portrait) lastLayoutPortrait = mode else lastLayout = mode
    }

    /** Stored camera-key order for [layout], or null when the user never moved a tile in it. */
    fun order(layout: GridLayoutMode): List<String>? = readOrder(KEY_ORDER_PREFIX + layout.name)

    /** Persists [keys] as the order for [layout]. */
    fun setOrder(
        layout: GridLayoutMode,
        keys: List<String>,
    ) = prefs.edit { putString(KEY_ORDER_PREFIX + layout.name, keys.joinToString(SEPARATOR)) }

    private fun readOrder(key: String): List<String>? =
        prefs.getString(key, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }

    private fun readLayout(key: String): GridLayoutMode =
        GridLayoutMode.fromStoredName(prefs.getString(key, null)) ?: GridLayoutMode.AUTO

    /** One key served both orientations until 0.3: a portrait layout stored there was chosen upright. */
    private fun migratePortraitLayout() {
        if (prefs.contains(KEY_LAST_LAYOUT_PORTRAIT)) return
        val stored = GridLayoutMode.fromStoredName(prefs.getString(KEY_LAST_LAYOUT, null)) ?: return
        if (!stored.isPortrait) return
        prefs.edit {
            putString(KEY_LAST_LAYOUT_PORTRAIT, stored.name)
            putString(KEY_LAST_LAYOUT, GridLayoutMode.AUTO.name)
        }
    }

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
        const val KEY_LAST_LAYOUT_PORTRAIT = "gridLayoutPortrait"
        const val KEY_ORDER_PREFIX = "arrangement."
    }
}
