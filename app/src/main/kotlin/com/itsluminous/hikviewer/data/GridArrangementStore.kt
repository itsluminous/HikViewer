package com.itsluminous.hikviewer.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode

/**
 * Where tiles sit in each grid layout, and which layout was used last.
 *
 * Each [GridLayoutMode] keeps its own ordered list of camera keys, so switching 4x3 → hero → 4x3
 * brings the 4x3 arrangement back, and the hero layout's position 0 is its hero. Cameras are
 * referenced by [com.itsluminous.hikviewer.core.devices.Camera.key] (`serial/channel`). The
 * store is a plain permutation; [com.itsluminous.hikviewer.ui.grid.GridArrangement] reconciles
 * it with the cameras actually visible. Shares the settings preferences so logout wipes it too.
 */
class GridArrangementStore(
    private val prefs: SharedPreferences,
) {
    /** The layout shown last, restored on launch. */
    var lastLayout: GridLayoutMode
        get() =
            prefs.getString(KEY_LAST_LAYOUT, null)?.let { runCatching { GridLayoutMode.valueOf(it) }.getOrNull() }
                ?: GridLayoutMode.AUTO
        set(value) = prefs.edit { putString(KEY_LAST_LAYOUT, value.name) }

    /** Stored camera-key order for [layout], or null when the user never moved a tile in it. */
    fun order(layout: GridLayoutMode): List<String>? =
        prefs.getString(KEY_ORDER_PREFIX + layout.name, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }

    /** Persists [keys] as the order for [layout]. */
    fun setOrder(
        layout: GridLayoutMode,
        keys: List<String>,
    ) = prefs.edit { putString(KEY_ORDER_PREFIX + layout.name, keys.joinToString(SEPARATOR)) }

    private companion object {
        const val SEPARATOR = ","

        /** Same key SettingsStore used for the chosen layout before arrangements existed, so upgrades keep it. */
        const val KEY_LAST_LAYOUT = "gridLayout"
        const val KEY_ORDER_PREFIX = "arrangement."
    }
}
