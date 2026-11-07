package com.itsluminous.hikviewer.ui.settings

import com.itsluminous.hikviewer.core.devices.Camera

/** One row of the visible-cameras list. */
data class CameraChoice(
    val camera: Camera,
    val visible: Boolean,
) {
    /** Preference key. */
    val key: String get() = camera.key
}

/**
 * The reorderable visible-camera list behind the settings screen, kept free of Views so the
 * pick-up / move / drop rules are unit-tested. Rows are every camera of the account: the chosen
 * ones first in their saved order, then the rest in channel order, unticked. A TV reorders with
 * pick-up / [moveBy] / drop on the D-pad; a phone drags a row with its finger ([move]), one step
 * at a time as it passes the neighbouring rows.
 */
class CameraOrderEditor(
    all: List<Camera>,
    chosenKeys: List<String>?,
) {
    private val rows: MutableList<CameraChoice>

    /** Index of the row being moved, or null. */
    var pickedUp: Int? = null
        private set

    init {
        val byKey = all.associateBy { it.key }
        val chosen = chosenKeys?.mapNotNull(byKey::get) ?: all.filter { it.hasSignal }
        val rest = all.filter { it !in chosen }
        rows = (chosen.map { CameraChoice(it, true) } + rest.map { CameraChoice(it, false) }).toMutableList()
    }

    /** Current rows. */
    val items: List<CameraChoice> get() = rows

    /** Ticks or unticks row [index]. */
    fun toggleVisible(index: Int) {
        rows[index] = rows[index].copy(visible = !rows[index].visible)
    }

    /** OK on a row's handle: picks it up, or drops it when it was picked up. Returns true when moving. */
    fun togglePickUp(index: Int): Boolean {
        pickedUp = if (pickedUp == index) null else index
        return pickedUp != null
    }

    /**
     * UP/DOWN while a row is picked up: moves it one step. Returns the new index, or null when no
     * row is picked up or it is already at the end.
     */
    fun moveBy(delta: Int): Int? {
        val from = pickedUp ?: return null
        val to = from + delta
        if (to !in rows.indices) return null
        rows.add(to, rows.removeAt(from))
        pickedUp = to
        return to
    }

    /**
     * A finger drag passing a neighbour (phones): row [from] takes position [to]. Nothing is
     * picked up; the drop that follows persists. Unchanged for an index outside the list.
     */
    fun move(
        from: Int,
        to: Int,
    ) {
        if (from == to || from !in rows.indices || to !in rows.indices) return
        rows.add(to, rows.removeAt(from))
    }

    /** Keys of the ticked rows in display order, what [com.itsluminous.hikviewer.data.SettingsStore] stores. */
    fun visibleKeys(): List<String> = rows.filter { it.visible }.map { it.key }
}
