package com.itsluminous.hikviewer.ui.grid

import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
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
}
