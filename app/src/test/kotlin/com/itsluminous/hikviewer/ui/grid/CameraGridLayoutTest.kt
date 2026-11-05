package com.itsluminous.hikviewer.ui.grid

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraGridLayoutTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val grid = CameraGridLayout(context)
    private val gap = context.resources.getDimensionPixelSize(com.itsluminous.hikviewer.R.dimen.tile_gap)

    private fun fill(n: Int) {
        repeat(n) { grid.addView(View(context).apply { isFocusable = true }) }
    }

    private fun layout(
        width: Int = 1920,
        height: Int = 1080,
    ) {
        grid.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        grid.layout(0, 0, width, height)
    }

    @Test
    fun `auto picks 4x3 for 12 tiles and lays them out on the geometry`() {
        fill(12)
        layout()
        assertThat(grid.shape.columns to grid.shape.rows).isEqualTo(4 to 3)
        val expected = GridGeometry.rects(grid.shape, 1920, 1080, gap)
        for (i in 0 until 12) {
            val child = grid.getChildAt(i)
            assertThat(child.visibility).isEqualTo(View.VISIBLE)
            assertThat(listOf(child.left, child.top, child.right, child.bottom))
                .containsExactly(expected[i].left, expected[i].top, expected[i].right, expected[i].bottom)
                .inOrder()
        }
    }

    @Test
    fun `tiles beyond capacity are hidden`() {
        fill(16)
        grid.mode = GridLayoutMode.GRID_3X3
        layout()
        assertThat((0 until 9).all { grid.getChildAt(it).visibility == View.VISIBLE }).isTrue()
        assertThat((9 until 16).all { grid.getChildAt(it).visibility == View.GONE }).isTrue()
        grid.mode = GridLayoutMode.GRID_4X4
        layout()
        assertThat((0 until 16).all { grid.getChildAt(it).visibility == View.VISIBLE }).isTrue()
    }

    @Test
    fun `hero tile is twice the size and tileAt resolves spans`() {
        fill(9)
        grid.mode = GridLayoutMode.HERO_1_5
        layout()
        val hero = grid.getChildAt(0)
        val small = grid.getChildAt(1)
        assertThat(hero.width).isEqualTo(small.width * 2 + gap)
        assertThat(hero.height).isEqualTo(small.height * 2 + gap)
        assertThat(grid.tileAt(0, 0)).isEqualTo(0)
        assertThat(grid.tileAt(1, 1)).isEqualTo(0)
        assertThat(grid.tileAt(2, 0)).isEqualTo(1)
        assertThat(grid.tileAt(0, 2)).isEqualTo(3)
        assertThat(grid.tileAt(9, 9)).isEqualTo(-1)
    }

    @Test
    fun `portrait falls back to two columns`() {
        fill(6)
        grid.portrait = true
        layout(width = 1080, height = 1920)
        assertThat(grid.shape.columns).isEqualTo(2)
        assertThat(grid.shape.rows).isEqualTo(3)
        assertThat(grid.getChildAt(1).left).isGreaterThan(grid.getChildAt(0).right)
        assertThat(grid.getChildAt(2).top).isGreaterThan(grid.getChildAt(0).bottom)
    }

    @Test
    fun `UP from the top row goes to the top bar, elsewhere stays in the grid`() {
        val bar = Button(context)
        val root = FrameLayout(context).also { it.addView(grid) }
        root.addView(bar)
        fill(8)
        layout()
        grid.onTopEdgeUp = { bar }
        assertThat(grid.focusSearch(grid.getChildAt(1), View.FOCUS_UP)).isSameInstanceAs(bar)
        assertThat(grid.focusSearch(grid.getChildAt(5), View.FOCUS_UP)).isNotSameInstanceAs(bar)
        grid.onTopEdgeUp = { null }
        assertThat(grid.focusSearch(grid.getChildAt(1), View.FOCUS_UP)).isNotSameInstanceAs(bar)
    }

    @Test
    fun `changing mode or orientation requests layout`() {
        fill(4)
        layout()
        grid.mode = GridLayoutMode.GRID_4X4
        assertThat(grid.isLayoutRequested).isTrue()
        layout()
        grid.portrait = true
        assertThat(grid.isLayoutRequested).isTrue()
    }

    @Test
    fun `order places children by position without reparenting, and hides the unlisted`() {
        fill(4)
        grid.mode = GridLayoutMode.GRID_3X3
        val first = grid.getChildAt(0)
        val last = grid.getChildAt(3)
        grid.order = listOf(3, 1, 2) // child 3 shown first, child 0 not shown
        layout()
        val rects = GridGeometry.rects(grid.shape, 1920, 1080, gap)
        assertThat(grid.childAtPosition(0)).isSameInstanceAs(last)
        assertThat(grid.positionOf(last)).isEqualTo(0)
        assertThat(last.left to last.top).isEqualTo(rects[0].left to rects[0].top)
        assertThat(grid.positionOf(first)).isEqualTo(-1)
        assertThat(first.visibility).isEqualTo(View.GONE)
        assertThat(grid.indexOfChild(last)).isEqualTo(3)
        grid.order = null
        layout()
        assertThat(grid.childAtPosition(0)).isSameInstanceAs(first)
        assertThat(first.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `neighbour steps one cell in a direction and stops at the edges`() {
        fill(12)
        grid.mode = GridLayoutMode.GRID_4X3
        layout()
        assertThat(grid.neighbour(0, +1, 0)).isEqualTo(1)
        assertThat(grid.neighbour(0, 0, +1)).isEqualTo(4)
        assertThat(grid.neighbour(0, -1, 0)).isEqualTo(-1)
        assertThat(grid.neighbour(0, 0, -1)).isEqualTo(-1)
        assertThat(grid.neighbour(11, +1, 0)).isEqualTo(-1)
        assertThat(grid.neighbour(5, -1, 0)).isEqualTo(4)
        grid.mode = GridLayoutMode.HERO_1_5
        layout()
        // Hero spans 2x2 at top-left: RIGHT from it skips its own span, DOWN from tile 1 lands on it.
        assertThat(grid.neighbour(0, +1, 0)).isEqualTo(1)
        assertThat(grid.neighbour(0, 0, +1)).isEqualTo(grid.tileAt(0, 2))
        assertThat(grid.neighbour(grid.tileAt(1, 2), 0, -1)).isEqualTo(0)
    }

    /** Fills the grid with real tiles, focusable in Robolectric's touch mode, and lays it out for [mode]. */
    private fun fillTiles(
        n: Int,
        mode: GridLayoutMode,
    ): List<CameraTileView> {
        val tiles = List(n) { CameraTileView(context).apply { isFocusableInTouchMode = true } }
        tiles.forEach(grid::addView)
        grid.mode = mode
        layout()
        return tiles
    }

    /** The tiles the layout shows, in grid-position order. */
    private fun shownTiles(): List<CameraTileView> =
        (0 until grid.shape.cells.size).mapNotNull { grid.childAtPosition(it) as? CameraTileView }

    @Test
    fun `every shown tile in every layout has a visible dot in its own top-right corner, coloured by flow`() {
        val margin = context.resources.getDimensionPixelSize(com.itsluminous.hikviewer.R.dimen.live_dot_margin)
        val green = context.getColor(com.itsluminous.hikviewer.R.color.live_dot_flowing)
        val red = context.getColor(com.itsluminous.hikviewer.R.color.live_dot_stalled)
        GridLayoutMode.values().forEach { mode ->
            grid.removeAllViews()
            grid.order = null
            val tiles = fillTiles(16, mode)
            tiles.forEachIndexed { i, tile -> tile.framesFlowing = i % 2 == 0 }
            val shown = shownTiles()
            assertThat(shown).hasSize(grid.shape.cells.size)
            shown.forEach { tile ->
                val dot = tile.findViewById<View>(com.itsluminous.hikviewer.R.id.live_dot)
                assertWithMessage("$mode").that(tile.visibility).isEqualTo(View.VISIBLE)
                assertWithMessage("$mode").that(dot.visibility).isEqualTo(View.VISIBLE)
                assertWithMessage("$mode").that(dot.top).isEqualTo(margin)
                assertWithMessage("$mode").that(dot.right).isEqualTo(tile.width - margin)
                val expected = if (tiles.indexOf(tile) % 2 == 0) green else red
                assertWithMessage("$mode").that(dot.backgroundTintList!!.defaultColor).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `after every D-pad move in every layout only the focused tile shows its border and is drawn last`() {
        val directions = listOf(View.FOCUS_RIGHT, View.FOCUS_DOWN, View.FOCUS_LEFT, View.FOCUS_UP)
        GridLayoutMode.values().forEach { mode ->
            grid.removeAllViews()
            grid.order = null
            fillTiles(16, mode)
            val shown = shownTiles()
            assertThat(shown.first().requestFocus()).isTrue()
            var moves = 0
            // Walk the grid: from every tile try every direction; each successful move is checked.
            shown.forEach { start ->
                start.requestFocus()
                assertFocusDrawn(mode, start)
                directions.forEach { direction ->
                    // What the window does for a D-pad key: FocusFinder, then requestFocus on the result.
                    val next =
                        android.view.FocusFinder
                            .getInstance()
                            .findNextFocus(grid, grid.findFocus(), direction) as? CameraTileView ?: return@forEach
                    assertThat(next.requestFocus()).isTrue()
                    moves++
                    assertFocusDrawn(mode, next)
                }
            }
            assertWithMessage("$mode").that(moves).isAtLeast(shown.size)
        }
    }

    private fun assertFocusDrawn(
        mode: GridLayoutMode,
        focused: CameraTileView,
    ) {
        assertWithMessage("$mode").that(grid.findFocus()).isSameInstanceAs(focused)
        shownTiles().forEach { tile ->
            assertWithMessage("$mode ${grid.positionOf(tile)}")
                .that(tile.focusBorderVisible)
                .isEqualTo(tile === focused)
        }
        assertWithMessage("$mode").that(grid.childDrawnAt(grid.childCount - 1)).isSameInstanceAs(focused)
        // Every child is still drawn exactly once.
        val drawn = (0 until grid.childCount).map { grid.childDrawnAt(it) }
        assertWithMessage("$mode").that(drawn).containsNoDuplicates()
        assertWithMessage("$mode").that(drawn).hasSize(grid.childCount)
    }

    @Test
    fun `a lifted tile keeps its border while focus is elsewhere, and clearing focus restores index order`() {
        val tiles = fillTiles(4, GridLayoutMode.GRID_3X3)
        tiles[2].lifted = true
        tiles[0].requestFocus()
        assertThat(tiles[2].focusBorderVisible).isTrue()
        assertThat(tiles[0].focusBorderVisible).isTrue()
        assertThat(grid.childDrawnAt(3)).isSameInstanceAs(tiles[0])
        tiles[0].clearFocus()
        assertThat(tiles[0].focusBorderVisible).isFalse()
        assertThat((0 until 4).map { grid.childDrawnAt(it) }).containsExactlyElementsIn(tiles).inOrder()
    }

    /** A manual scheduler standing in for `postDelayed`: the test's clock. */
    private var armed: Pair<Runnable, Long>? = null

    /** Installs a 5 s focus timer on the manual clock; returns the tiles. */
    private fun tilesWithFocusTimer(n: Int = 4): List<CameraTileView> {
        val tiles = fillTiles(n, GridLayoutMode.GRID_3X3)
        grid.installFocusTimer(
            autoHideMillis = 5_000,
            schedule = { r, ms -> armed = r to ms },
            cancel = { armed = null },
        )
        return tiles
    }

    private fun fire() {
        val (r, _) = checkNotNull(armed) { "no timer armed" }
        armed = null
        r.run()
    }

    @Test
    fun `the focused tile's border hides 5 s after the last key and focus stays put`() {
        val tiles = tilesWithFocusTimer()
        assertThat(tiles[1].requestFocus()).isTrue()
        assertThat(tiles[1].focusBorderVisible).isTrue()
        assertThat(armed?.second).isEqualTo(5_000)
        fire()
        assertThat(grid.focusVisualShown).isFalse()
        assertThat(tiles[1].focusBorderVisible).isFalse()
        assertThat(tiles[1].isFocused).isTrue()
        assertThat(tiles[1].hasFocus()).isTrue()
        assertThat(grid.findFocus()).isSameInstanceAs(tiles[1])
        assertThat(armed).isNull()
        // A key to the focused tile passes through the grid: the border is back at once, timer re-armed.
        grid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assertThat(tiles[1].focusBorderVisible).isTrue()
        assertThat(tiles[1].isFocused).isTrue()
        assertThat(armed?.second).isEqualTo(5_000)
        // Key-up is not activity; the pending timer is left alone.
        val pending = armed
        grid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertThat(armed).isSameInstanceAs(pending)
    }

    @Test
    fun `a mouse move or any hover over the grid shows the border again, a touch does not`() {
        val tiles = tilesWithFocusTimer()
        tiles[0].requestFocus()
        fire()
        assertThat(tiles[0].focusBorderVisible).isFalse()
        val touch = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 10f, 10f, 0)
        touch.source = InputDevice.SOURCE_TOUCHSCREEN
        grid.dispatchGenericMotionEvent(touch)
        grid.dispatchTouchEvent(touch)
        assertThat(tiles[0].focusBorderVisible).isFalse()
        val scroll = MotionEvent.obtain(0, 0, MotionEvent.ACTION_SCROLL, 10f, 10f, 0)
        scroll.source = InputDevice.SOURCE_MOUSE
        grid.dispatchGenericMotionEvent(scroll)
        assertThat(tiles[0].focusBorderVisible).isTrue()
        assertThat(armed?.second).isEqualTo(5_000)
        fire()
        assertThat(tiles[0].focusBorderVisible).isFalse()
        val hover = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_MOVE, 10f, 10f, 0)
        hover.source = InputDevice.SOURCE_STYLUS
        grid.dispatchGenericMotionEvent(hover)
        assertThat(tiles[0].focusBorderVisible).isTrue()
        assertThat(tiles[0].isFocused).isTrue()
        assertThat(armed?.second).isEqualTo(5_000)
    }

    @Test
    fun `focus arriving on a tile shows the border, and a tile added while hidden starts hidden`() {
        val tiles = tilesWithFocusTimer()
        tiles[0].requestFocus()
        fire()
        assertThat(grid.focusVisualShown).isFalse()
        val late = CameraTileView(context).apply { isFocusableInTouchMode = true }
        grid.addView(late)
        assertThat(late.focusVisualShown).isFalse()
        // Moving focus (what a D-pad key does) is activity: every tile is switched back on.
        assertThat(tiles[2].requestFocus()).isTrue()
        assertThat(grid.focusVisualShown).isTrue()
        assertThat(tiles[2].focusBorderVisible).isTrue()
        assertThat(late.focusVisualShown).isTrue()
        assertThat(tiles[0].focusBorderVisible).isFalse() // not focused any more
    }

    @Test
    fun `installing the production timer shows a focused tile at once`() {
        val tiles = tilesWithFocusTimer()
        tiles[3].requestFocus()
        fire()
        assertThat(tiles[3].focusBorderVisible).isFalse()
        grid.focusAutoHideMillis = 1_000
        assertThat(tiles[3].focusBorderVisible).isTrue()
        assertThat(tiles[3].isFocused).isTrue()
    }
}
