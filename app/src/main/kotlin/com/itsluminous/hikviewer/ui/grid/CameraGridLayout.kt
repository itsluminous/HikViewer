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
 * because the hero tile spans cells. Children are laid out in index order; children past the
 * shape's capacity are hidden. [onTopEdgeUp] lets the screen route UP from the top row to its top
 * bar, which the default focus search cannot reach because the bar is invisible until a key arrives.
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
                val rect = rects.getOrNull(i)
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
                val rect = rects.getOrNull(i) ?: continue
                getChildAt(i).layout(rect.left, rect.top, rect.right, rect.bottom)
            }
        }

        override fun focusSearch(
            focused: View?,
            direction: Int,
        ): View? {
            if (direction == FOCUS_UP && focused != null) {
                val index = indexOfChild(focused)
                if (index >= 0 && shape.cells.getOrNull(index)?.row == 0) {
                    onTopEdgeUp?.invoke()?.let { return it }
                }
            }
            return super.focusSearch(focused, direction)
        }

        /** Index of the tile at cell ([column], [row]), or -1. */
        fun tileAt(
            column: Int,
            row: Int,
        ): Int = shape.cells.indexOfFirst { c ->
            column in c.column until c.column + c.columnSpan && row in c.row until c.row + c.rowSpan
        }
    }
