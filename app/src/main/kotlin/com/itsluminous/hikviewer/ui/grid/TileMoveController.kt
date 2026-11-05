package com.itsluminous.hikviewer.ui.grid

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Move mode of the grid: one tile is lifted and swapped with its neighbours until it is dropped.
 *
 * D-pad keys swap the lifted tile with the neighbour in that direction, a mouse or finger drag
 * swaps it with the tile under the pointer, OK or release drops it, Back puts the original
 * arrangement back. Every swap goes through the view model, which persists the order for the
 * current layout and re-renders; the lifted *view* is the same object throughout (the grid only
 * changes positions), so focus stays on it by itself. Kept out of [GridActivity] so the screen's
 * class reads as the screen and this reads as the gesture.
 */
class TileMoveController(
    private val grid: CameraGridLayout,
    private val hint: View,
    private val viewModel: GridViewModel,
) {
    /** The lifted tile while a move is in progress; its index is read from the grid, since swaps move it. */
    private var moving: CameraTileView? = null

    /** Arrangement when the move began, restored on Back. */
    private var origin: List<String>? = null

    /** True while a tile is lifted. */
    val isMoving: Boolean get() = moving != null

    /** The lifted tile, for whoever needs to give it focus back (the tile menu does). */
    val lifted: CameraTileView? get() = moving

    /** Lifts the tile at grid [position]. */
    fun begin(position: Int) {
        val tile = grid.childAtPosition(position) as? CameraTileView ?: return
        moving = tile
        origin = (viewModel.state.value as? GridUiState.Ready)?.cameras?.map { it.key }
        tile.lifted = true
        tile.requestFocus()
        hint.visibility = View.VISIBLE
    }

    /** Drops the lifted tile where it is. */
    fun end() {
        moving?.lifted = false
        moving = null
        origin = null
        hint.visibility = View.GONE
    }

    /** Back: drops the tile and restores the arrangement the move started from. */
    fun cancel() {
        val keys = origin
        val tile = moving
        end()
        if (keys == null || tile == null) return
        // The lifted camera's index in the original arrangement is where focus must land.
        val key =
            (viewModel.state.value as? GridUiState.Ready)
                ?.cameras
                ?.getOrNull(grid.positionOf(tile))
                ?.key
        viewModel.focusedIndex = keys.indexOf(key).coerceAtLeast(0)
        viewModel.restoreArrangement(keys)
    }

    /** Handles a key while moving; false when it is not a move key. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            // Swallow the UP of every key we handled on DOWN (OK's UP would otherwise click the tile).
            return event.keyCode in MOVE_KEYS
        }
        val from = grid.positionOf(moving ?: return false)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> cancel()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> end()
            KeyEvent.KEYCODE_DPAD_LEFT -> swapLifted(grid.neighbour(from, -1, 0))
            KeyEvent.KEYCODE_DPAD_RIGHT -> swapLifted(grid.neighbour(from, +1, 0))
            KeyEvent.KEYCODE_DPAD_UP -> swapLifted(grid.neighbour(from, 0, -1))
            KeyEvent.KEYCODE_DPAD_DOWN -> swapLifted(grid.neighbour(from, 0, +1))
            else -> return false
        }
        return true
    }

    /** Drag with a finger or a pressed mouse button: swap with whatever tile is under the pointer. */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val target = tileUnder(ev.rawX, ev.rawY)
                if (target >= 0) swapLifted(target)
            }
            MotionEvent.ACTION_UP -> end()
            MotionEvent.ACTION_CANCEL -> cancel()
            else -> return false
        }
        return true
    }

    /**
     * Swaps the lifted tile with the one at [target]. The view model persists the new order and
     * moves focus to [target]; the re-render then moves the lifted view there.
     */
    private fun swapLifted(target: Int) {
        val from = grid.positionOf(moving ?: return)
        if (target < 0 || target == from || target >= grid.childCount) return
        viewModel.moveTile(from, target)
    }

    private fun tileUnder(
        rawX: Float,
        rawY: Float,
    ): Int {
        val origin = IntArray(2).also(grid::getLocationOnScreen)
        val x = (rawX - origin[0]).toInt()
        val y = (rawY - origin[1]).toInt()
        for (i in 0 until grid.childCount) {
            val c = grid.getChildAt(i)
            if (x in c.left until c.right && y in c.top until c.bottom) return grid.positionOf(c)
        }
        return -1
    }

    private companion object {
        val MOVE_KEYS =
            setOf(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
            )
    }
}
