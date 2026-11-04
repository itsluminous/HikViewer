package com.itsluminous.hikviewer.ui.grid

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GridGeometryTest {
    @Test
    fun `auto resolves by tile count`() {
        assertThat(GridLayoutMode.AUTO.resolve(1)).isEqualTo(GridLayoutMode.GRID_3X3)
        assertThat(GridLayoutMode.AUTO.resolve(9)).isEqualTo(GridLayoutMode.GRID_3X3)
        assertThat(GridLayoutMode.AUTO.resolve(10)).isEqualTo(GridLayoutMode.GRID_4X3)
        assertThat(GridLayoutMode.AUTO.resolve(12)).isEqualTo(GridLayoutMode.GRID_4X3)
        assertThat(GridLayoutMode.AUTO.resolve(13)).isEqualTo(GridLayoutMode.GRID_4X4)
        assertThat(GridLayoutMode.AUTO.resolve(40)).isEqualTo(GridLayoutMode.GRID_4X4)
        assertThat(GridLayoutMode.GRID_3X3.resolve(40)).isEqualTo(GridLayoutMode.GRID_3X3)
        assertThat(GridLayoutMode.HERO.resolve(2)).isEqualTo(GridLayoutMode.HERO)
    }

    @Test
    fun `fixed shapes fill row by row`() {
        val shape = GridGeometry.shape(GridLayoutMode.GRID_4X3, 12)
        assertThat(shape.columns).isEqualTo(4)
        assertThat(shape.rows).isEqualTo(3)
        assertThat(shape.capacity).isEqualTo(12)
        assertThat(shape.cells[5]).isEqualTo(Cell(1, 1))
        assertThat(shape.cells[11]).isEqualTo(Cell(3, 2))
        assertThat(GridGeometry.shape(GridLayoutMode.AUTO, 16).capacity).isEqualTo(16)
        assertThat(GridGeometry.shape(GridLayoutMode.GRID_3X3, 16).capacity).isEqualTo(9)
    }

    @Test
    fun `hero grows with the tile count`() {
        fun hero(n: Int) = GridGeometry.shape(GridLayoutMode.HERO, n)
        assertThat(hero(1).columns to hero(1).rows).isEqualTo(3 to 3)
        assertThat(hero(6).columns to hero(6).rows).isEqualTo(3 to 3)
        assertThat(hero(7).columns to hero(7).rows).isEqualTo(4 to 3)
        assertThat(hero(9).columns to hero(9).rows).isEqualTo(4 to 3)
        assertThat(hero(10).columns to hero(10).rows).isEqualTo(4 to 4)
        assertThat(hero(13).columns to hero(13).rows).isEqualTo(4 to 4)
        assertThat(hero(14).columns to hero(14).rows).isEqualTo(5 to 4)
        assertThat(hero(17).capacity).isEqualTo(17)
        assertThat(hero(40).capacity).isEqualTo(17)
    }

    @Test
    fun `hero cell order is top-left 2x2 then right then below`() {
        val shape = GridGeometry.shape(GridLayoutMode.HERO, 9)
        assertThat(shape.cells.first()).isEqualTo(Cell(0, 0, 2, 2))
        assertThat(
            shape.cells.drop(1).take(4),
        ).containsExactly(Cell(2, 0), Cell(3, 0), Cell(2, 1), Cell(3, 1)).inOrder()
        assertThat(shape.cells.drop(5)).containsExactly(Cell(0, 2), Cell(1, 2), Cell(2, 2), Cell(3, 2)).inOrder()
    }

    @Test
    fun `portrait uses two columns and a full-width hero`() {
        val fixed = GridGeometry.shape(GridLayoutMode.AUTO, 5, portrait = true)
        assertThat(fixed.columns).isEqualTo(2)
        assertThat(fixed.rows).isEqualTo(3)
        assertThat(fixed.cells[4]).isEqualTo(Cell(0, 2))
        val hero = GridGeometry.shape(GridLayoutMode.HERO, 5, portrait = true)
        assertThat(hero.columns).isEqualTo(2)
        assertThat(hero.rows).isEqualTo(4)
        assertThat(hero.cells.first()).isEqualTo(Cell(0, 0, 2, 2))
        assertThat(hero.cells[1]).isEqualTo(Cell(0, 2))
        assertThat(GridGeometry.shape(GridLayoutMode.AUTO, 0, portrait = true).rows).isEqualTo(1)
    }

    @Test
    fun `rects tile the area with gaps and spans`() {
        val shape = GridGeometry.shape(GridLayoutMode.HERO, 9)
        val rects = GridGeometry.rects(shape, width = 1900, height = 1060, gap = 10)
        // 4 columns: (1900 - 30) / 4 = 467; 3 rows: (1060 - 20) / 3 = 346.
        assertThat(rects[0]).isEqualTo(TileRect(0, 0, 467 * 2 + 10, 346 * 2 + 10))
        assertThat(rects[1]).isEqualTo(TileRect(2 * 477, 0, 2 * 477 + 467, 346))
        assertThat(rects[5]).isEqualTo(TileRect(0, 2 * 356, 467, 2 * 356 + 346))
        assertThat(rects.last().right).isAtMost(1900)
        assertThat(rects.last().bottom).isAtMost(1060)
        assertThat(rects[0].width).isEqualTo(944)
    }

    @Test
    fun `every fixed layout places tiles without overlap`() {
        for (mode in listOf(
            GridLayoutMode.GRID_3X3,
            GridLayoutMode.GRID_4X3,
            GridLayoutMode.GRID_4X4,
            GridLayoutMode.HERO,
        )) {
            val shape = GridGeometry.shape(mode, 16)
            val rects = GridGeometry.rects(shape, 1920, 1080, 6)
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    val a = rects[i]
                    val b = rects[j]
                    val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                    assertThat(overlap).isFalse()
                }
            }
        }
    }
}
