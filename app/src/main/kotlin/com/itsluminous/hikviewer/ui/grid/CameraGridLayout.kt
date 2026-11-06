package com.itsluminous.hikviewer.ui.grid

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.ui.common.InactivityTimer

/**
 * Lays its children out on the cells of [GridGeometry] for the current [mode].
 *
 * A custom `ViewGroup` rather than `GridLayout`/`RecyclerView` because the tiles are a fixed, small
 * set of `SurfaceView`s that must never be recycled (recreating a surface restarts its decoder) and
 * because the hero tile spans cells. Shapes (see [GridGeometry]): the fixed grids are 3x3, 4x3
 * and 4x4; the 1 + N layouts are square grids with the hero top-left: 1 + 5 has 3 columns
 * (hero 2x2), 1 + 7 has 4 (hero 3x3), 1 + 9 has 5, 1 + 11 has 6 and 1 + 15 has 8, so every
 * tile keeps the screen's aspect ratio. Children fill the grid positions in index order unless
 * [order] says otherwise: rearranging tiles changes [order], never the child list, because
 * detaching a `SurfaceView` destroys its surface. Children past the shape's capacity are hidden.
 * [onTopEdgeUp] lets the screen route UP from the top row to its top bar, which the default focus
 * search cannot reach because the bar is invisible until a key arrives.
 *
 * The child holding focus is drawn last. Each tile's `SurfaceView` punches a hole in the window
 * when it draws, and a tile drawn after its neighbour can only ever cover, never be covered by, the
 * neighbour's punch: drawing the focused tile last keeps its border on top whatever a device's
 * `SurfaceView` clears. Focus changes invalidate the grid so the order takes effect on that frame.
 *
 * The focus border is a transient visual like the top bar: an [InactivityTimer] hides it on every
 * tile [focusAutoHideMillis] after the last key or pointer event seen by the grid (every key to a
 * focused tile passes through here, as does a pointer over the grid) and shows it again at the
 * next one, or when focus arrives on a tile. Only the drawing changes; focus never moves.
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

        /** Child index of the tile that holds focus, or -1; drawn last. */
        private var focusedChild = -1

        /** The tiles' border switch, driven by [focusTimer]; hidden until the first input or focus. */
        private val focusVisual =
            object : InactivityTimer.Visual {
                override var isShown: Boolean = true
                    private set

                override fun show() = setShown(true)

                override fun hide() = setShown(false)

                private fun setShown(value: Boolean) {
                    isShown = value
                    for (i in 0 until childCount) (getChildAt(i) as? CameraTileView)?.focusVisualShown = value
                }
            }

        private var focusTimer = newFocusTimer(AppGraph.DEFAULT_TOP_BAR_AUTO_HIDE_MILLIS)

        /**
         * Idle time after which the focused tile stops drawing its border; the screen sets it from
         * the app graph so tests can shorten it, and the top bar's 5 s is the default.
         */
        var focusAutoHideMillis: Long = AppGraph.DEFAULT_TOP_BAR_AUTO_HIDE_MILLIS
            set(value) {
                if (field == value) return
                field = value
                installFocusTimer(newFocusTimer(value))
            }

        private fun newFocusTimer(autoHideMillis: Long) =
            InactivityTimer(focusVisual, autoHideMillis, { r, ms -> postDelayed(r, ms) }, { removeCallbacks(it) })

        /**
         * Puts the border on a timer with an injected clock (tests); a focused tile is shown again,
         * and the new timer armed, at once.
         */
        internal fun installFocusTimer(
            autoHideMillis: Long,
            schedule: (Runnable, Long) -> Unit,
            cancel: (Runnable) -> Unit,
        ) = installFocusTimer(InactivityTimer(focusVisual, autoHideMillis, schedule, cancel))

        private fun installFocusTimer(timer: InactivityTimer) {
            focusTimer.hideNow()
            focusTimer = timer
            if (hasFocus()) focusTimer.onUserActivity()
        }

        /** True while the focused tile draws its border. */
        val focusVisualShown: Boolean get() = focusVisual.isShown

        init {
            isChildrenDrawingOrderEnabled = true
        }

        override fun onViewAdded(child: View?) {
            super.onViewAdded(child)
            (child as? CameraTileView)?.focusVisualShown = focusVisual.isShown
        }

        override fun requestChildFocus(
            child: View?,
            focused: View?,
        ) {
            super.requestChildFocus(child, focused)
            setFocusedChildIndex(indexOfChild(child))
            // Focus arriving (first render, back from another screen, a D-pad move) is activity too,
            // unless the app moved it on its own while the user was idle (focusQuietly).
            if (!quietFocus) focusTimer.onUserActivity()
        }

        private var quietFocus = false

        /**
         * Focuses the tile at [position] without showing its border: the app moved focus by
         * itself while the user was idle (the TV's top bar handing focus back), so the border
         * stays hidden until the next key or pointer event, like any other idle state. Returns
         * whether the tile took focus.
         */
        fun focusQuietly(position: Int): Boolean {
            val tile = childAtPosition(position) ?: return false
            quietFocus = true
            try {
                return tile.requestFocus()
            } finally {
                quietFocus = false
            }
        }

        /** Every key to a focused tile is dispatched through its ancestors: a press shows the border. */
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) focusTimer.onUserActivity()
            return super.dispatchKeyEvent(event)
        }

        /**
         * A mouse (any of its events) or a hovering pointer over the grid shows the border. Touch
         * goes through `dispatchTouchEvent` and never counts: a finger draws no focus anyway.
         */
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            val hover =
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                    event.actionMasked == MotionEvent.ACTION_HOVER_MOVE
            if (hover || event.isFromSource(InputDevice.SOURCE_MOUSE)) focusTimer.onUserActivity()
            return super.dispatchGenericMotionEvent(event)
        }

        override fun clearChildFocus(child: View?) {
            super.clearChildFocus(child)
            setFocusedChildIndex(-1)
        }

        private fun setFocusedChildIndex(index: Int) {
            if (focusedChild == index) return
            focusedChild = index
            invalidate()
        }

        /** Index order, except that the focused child swaps with the last slot. */
        override fun getChildDrawingOrder(
            childCount: Int,
            drawingPosition: Int,
        ): Int {
            val focused = focusedChild
            if (focused < 0 || focused >= childCount) return drawingPosition
            val last = childCount - 1
            return when (drawingPosition) {
                last -> focused
                focused -> last
                else -> drawingPosition
            }
        }

        /** The child drawn at [drawingPosition] of [childCount]; the focused one comes last. */
        fun childDrawnAt(drawingPosition: Int): View = getChildAt(getChildDrawingOrder(childCount, drawingPosition))

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
