package com.itsluminous.hikviewer.ui.grid

import com.itsluminous.hikviewer.core.devices.Camera

/**
 * Pure rules for the per-layout tile arrangement, so they are unit-tested without Android.
 *
 * An arrangement is an ordered list of camera keys stored for one [GridLayoutMode]. It is a
 * permutation of the cameras Settings makes visible: [apply] puts the stored keys first (in
 * stored order, skipping keys whose camera is no longer shown) and appends cameras the stored
 * list does not know, in their Settings order. In the hero layout position 0 is the hero.
 */
object GridArrangement {
    /** [visible] reordered by [order]; unknown cameras appended, vanished keys dropped. */
    fun apply(
        visible: List<Camera>,
        order: List<String>?,
    ): List<Camera> {
        if (order.isNullOrEmpty()) return visible
        val byKey = visible.associateBy { it.key }
        val placed = order.mapNotNull(byKey::get)
        val placedKeys = placed.mapTo(HashSet()) { it.key }
        return placed + visible.filter { it.key !in placedKeys }
    }

    /** [keys] with the entries at [from] and [to] exchanged; unchanged for an out-of-range index. */
    fun swapped(
        keys: List<String>,
        from: Int,
        to: Int,
    ): List<String> {
        if (from == to || from !in keys.indices || to !in keys.indices) return keys
        return keys.toMutableList().also {
            it[from] = keys[to]
            it[to] = keys[from]
        }
    }

    /** [keys] with the entry at [index] moved to the front (the hero slot); the rest keep their order. */
    fun movedToFront(
        keys: List<String>,
        index: Int,
    ): List<String> {
        if (index !in keys.indices) return keys
        return listOf(keys[index]) + keys.filterIndexed { i, _ -> i != index }
    }
}
