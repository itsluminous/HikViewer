package com.itsluminous.hikviewer.ui.grid

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.itsluminous.hikviewer.R

/**
 * Lays its children out on the cells of [GridGeometry] for the current [mode].
 *
 * A custom `ViewGroup` rather than `GridLayout`/`RecyclerView` because the tiles are a fixed, small
 * set of `SurfaceView`s that must never be recycled (recreating a surface restarts its decoder) and
 * because the hero tile spans cells. Shapes (see [GridGeometry]): the fixed grids are 3x3, 4x3
 * and 4x4; the 1 + N layouts are square grids with the hero top-left — 1 + 5 has 3 columns
 * (hero 2x2), 1 + 7 has 4 (hero 3x3), 1 + 9 has 5, 1 + 11 has 6 and 1 + 15 has 8 — so every
 * tile keeps the screen's aspect ratio. Children fill the grid positions in index order unless
 * [order] says otherwise: rearranging tiles changes [order], never the child list, because
 * detaching a `SurfaceView` destroys its surface. Children past the shape's capacity are hidden.
 * [onTopEdgeUp] lets the screen route UP from the top row to its top bar, which the default focus
 * search cannot reach because the bar is invisible until a key arrives.
 */
class CameraGridLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : ViewGroup(context, attrs) {
        /** Grid shape; relayouts on change. */
        var mode: GridLayoutMode = GridLayoutMode.AUTO
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        /** True on narrow (portrait phone) screens: two columns. */
        var portrait: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        /** Called when UP is pressed on a tile in the top row; returns the view to focus, or null. */
        var onTopEdgeUp: (() -> View?)? = null

        /**
         * Child index shown at each grid position (`order[position] = childIndex`), or null for
         * children in index order. A child the list does not name is hidden.
         */
        var order: List<Int>? = null
            set(value) {
                if (field == value) return
                field = value
                requestLayout()
            }

        /** The child shown at grid [position], or null. */
        fun childAtPosition(position: Int): View? = getChildAt(order?.getOrNull(position) ?: position)

        /** The grid position of [child], or -1 when it is not a child or [order] hides it. */
        fun positionOf(child: View): Int {
            val index = indexOfChild(child)
            if (index < 0) return -1
            return order?.indexOf(index) ?: index
        }

        private val gap = resources.getDimensionPixelSize(R.dimen.tile_gap)

        /** The shape used by the last layout pass. */
        var shape: GridShape = GridGeometry.shape(mode, 0, portrait)
            private set

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            shape = GridGeometry.shape(mode, childCount, portrait)
            val rects = GridGeometry.rects(shape, width, height, gap)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val rect = rects.getOrNull(positionOf(child))
                if (rect == null) {
                    child.visibility = GONE
                    continue
                }
                child.visibility = VISIBLE
                child.measure(
                    MeasureSpec.makeMeasureSpec(rect.width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rect.height, MeasureSpec.EXACTLY),
                )
            }
            setMeasuredDimension(width, height)
        }

        override fun onLayout(
            changed: Boolean,
            l: Int,
            t: Int,
            r: Int,
            b: Int,
        ) {
            val rects = GridGeometry.rects(shape, r - l, b - t, gap)
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val rect = rects.getOrNull(positionOf(child)) ?: continue
                child.layout(rect.left, rect.top, rect.right, rect.bottom)
            }
        }

        override fun focusSearch(
            focused: View?,
            direction: Int,
        ): View? {
            if (direction == FOCUS_UP && focused != null) {
                val position = positionOf(focused)
                if (position >= 0 && shape.cells.getOrNull(position)?.row == 0) {
                    onTopEdgeUp?.invoke()?.let { return it }
                }
            }
            return super.focusSearch(focused, direction)
        }

        /**
         * Grid position one step from [position] in direction ([dx], [dy]) in cells, measured from
         * that tile's top-left cell and stepping past its own span; -1 at the grid's edge.
         */
        fun neighbour(
            position: Int,
            dx: Int,
            dy: Int,
        ): Int {
            val cell = shape.cells.getOrNull(position) ?: return -1
            val column = if (dx > 0) cell.column + cell.columnSpan else cell.column + dx
            val row = if (dy > 0) cell.row + cell.rowSpan else cell.row + dy
            val target = tileAt(column, row)
            return if (target == position || target >= childCount) -1 else target
        }

        /** Grid position of the tile at cell ([column], [row]), or -1. */
        fun tileAt(
            column: Int,
            row: Int,
        ): Int = shape.cells.indexOfFirst { c ->
            column in c.column until c.column + c.columnSpan && row in c.row until c.row + c.rowSpan
        }
    }
