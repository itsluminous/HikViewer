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
        assertThat(GridLayoutMode.HERO_1_5.resolve(2)).isEqualTo(GridLayoutMode.HERO_1_5)
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
    fun `each 1+N layout is a square grid with a k x k hero and 2k+1 companions`() {
        val expected =
            mapOf(
                GridLayoutMode.HERO_1_5 to Triple(3, 2, 6),
                GridLayoutMode.HERO_1_7 to Triple(4, 3, 8),
                GridLayoutMode.HERO_1_9 to Triple(5, 4, 10),
                GridLayoutMode.HERO_1_11 to Triple(6, 5, 12),
                GridLayoutMode.HERO_1_15 to Triple(8, 7, 16),
            )
        assertThat(GridLayoutMode.heroModes.filter { !it.isPortraitHero })
            .containsExactlyElementsIn(expected.keys)
            .inOrder()
        expected.forEach { (mode, spec) ->
            val (columns, heroSpan, capacity) = spec
            val shape = GridGeometry.shape(mode, tileCount = 40)
            assertThat(shape.columns).isEqualTo(columns)
            assertThat(shape.rows).isEqualTo(columns)
            assertThat(shape.capacity).isEqualTo(capacity)
            assertThat(mode.capacity).isEqualTo(capacity)
            assertThat(mode.heroSpan).isEqualTo(heroSpan)
            assertThat(shape.cells.first()).isEqualTo(Cell(0, 0, heroSpan, heroSpan))
            // Right column top to bottom, then the bottom row left to right.
            val right = (0 until heroSpan).map { Cell(heroSpan, it) }
            val below = (0..heroSpan).map { Cell(it, heroSpan) }
            assertThat(shape.cells.drop(1)).containsExactlyElementsIn(right + below).inOrder()
            assertThat(mode.landscapeCapacity).isEqualTo(capacity)
        }
    }

    @Test
    fun `each even 1+N layout fills the portrait rows and takes the next smaller square grid turned`() {
        val even =
            listOf(
                GridLayoutMode.HERO_1_6,
                GridLayoutMode.HERO_1_8,
                GridLayoutMode.HERO_1_10,
                GridLayoutMode.HERO_1_12,
                GridLayoutMode.HERO_1_14,
                GridLayoutMode.HERO_1_16,
            )
        assertThat(GridLayoutMode.heroModes.filter { it.isPortraitHero }).containsExactlyElementsIn(even).inOrder()
        even.forEach { mode ->
            val n = mode.heroCompanions
            val upright = GridGeometry.shape(mode, tileCount = 40, portrait = true)
            assertThat(upright.columns).isEqualTo(2)
            assertThat(upright.rows).isEqualTo(2 + n / 2)
            assertThat(upright.capacity).isEqualTo(n + 1)
            // Every cell is a tile, apart from the three extra cells the hero spans.
            assertThat(upright.capacity).isEqualTo(upright.columns * upright.rows - 3)
            assertThat(upright.cells.last()).isEqualTo(Cell(1, upright.rows - 1)) // no empty cell
            val turned = GridGeometry.shape(mode, tileCount = 40, portrait = false)
            val k = n / 2 - 1
            assertThat(mode.heroSpan).isEqualTo(k)
            assertThat(turned.columns).isEqualTo(k + 1)
            assertThat(turned.rows).isEqualTo(k + 1)
            assertThat(turned.capacity).isEqualTo(n) // one tile fewer: the last one is evicted
            assertThat(mode.landscapeCapacity).isEqualTo(n)
            assertThat(turned.cells.first()).isEqualTo(Cell(0, 0, k, k))
        }
        // An odd layout upright leaves the last cell empty, which is why phones get the even ones.
        val oddUpright = GridGeometry.shape(GridLayoutMode.HERO_1_5, 6, portrait = true)
        assertThat(oddUpright.capacity).isEqualTo(6)
        assertThat(oddUpright.rows * oddUpright.columns - 3).isEqualTo(7)
    }

    @Test
    fun `1+N capacity does not depend on the tile count`() {
        assertThat(GridGeometry.shape(GridLayoutMode.HERO_1_7, 2).capacity).isEqualTo(8)
        assertThat(GridGeometry.shape(GridLayoutMode.HERO_1_7, 16).capacity).isEqualTo(8)
    }

    @Test
    fun `portrait uses two columns and a full-width hero`() {
        val fixed = GridGeometry.shape(GridLayoutMode.AUTO, 5, portrait = true)
        assertThat(fixed.columns).isEqualTo(2)
        assertThat(fixed.rows).isEqualTo(3)
        assertThat(fixed.cells[4]).isEqualTo(Cell(0, 2))
        val hero = GridGeometry.shape(GridLayoutMode.HERO_1_5, 5, portrait = true)
        assertThat(hero.columns).isEqualTo(2)
        assertThat(hero.rows).isEqualTo(5)
        assertThat(hero.capacity).isEqualTo(6)
        assertThat(hero.cells.first()).isEqualTo(Cell(0, 0, 2, 2))
        assertThat(hero.cells[1]).isEqualTo(Cell(0, 2))
        assertThat(GridGeometry.shape(GridLayoutMode.HERO_1_7, 8, portrait = true).rows).isEqualTo(6)
        assertThat(GridGeometry.shape(GridLayoutMode.AUTO, 0, portrait = true).rows).isEqualTo(1)
    }

    @Test
    fun `rects tile the area with gaps and spans`() {
        val shape = GridGeometry.shape(GridLayoutMode.HERO_1_7, 8)
        val rects = GridGeometry.rects(shape, width = 1900, height = 1060, gap = 10)
        // 4 columns: (1900 - 30) / 4 = 467; 4 rows: (1060 - 30) / 4 = 257.
        assertThat(rects[0]).isEqualTo(TileRect(0, 0, 467 * 3 + 20, 257 * 3 + 20))
        assertThat(rects[1]).isEqualTo(TileRect(3 * 477, 0, 3 * 477 + 467, 257))
        assertThat(rects[4]).isEqualTo(TileRect(0, 3 * 267, 467, 3 * 267 + 257))
        assertThat(rects.last().right).isAtMost(1900)
        assertThat(rects.last().bottom).isAtMost(1060)
        assertThat(rects[0].width).isEqualTo(1421)
    }

    @Test
    fun `every layout places tiles without overlap`() {
        for (mode in GridLayoutMode.entries) {
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

    @Test
    fun `menu lists 1+N layouts only when every tile has a camera`() {
        assertThat(GridLayoutMode.available(6))
            .containsExactly(
                GridLayoutMode.AUTO,
                GridLayoutMode.GRID_3X3,
                GridLayoutMode.HERO_1_5,
            ).inOrder()
        assertThat(GridLayoutMode.available(7, portraitPhone = true))
            .containsExactly(
                GridLayoutMode.AUTO,
                GridLayoutMode.GRID_3X3,
                GridLayoutMode.HERO_1_6,
            ).inOrder()
        assertThat(GridLayoutMode.available(40, portraitPhone = true).filter { it.isHero })
            .containsExactlyElementsIn(GridLayoutMode.heroModes.filter { it.isPortraitHero })
            .inOrder()
        assertThat(GridLayoutMode.available(5)).containsExactly(GridLayoutMode.AUTO, GridLayoutMode.GRID_3X3)
        assertThat(GridLayoutMode.available(14))
            .containsExactly(
                GridLayoutMode.AUTO,
                GridLayoutMode.GRID_3X3,
                GridLayoutMode.GRID_4X3,
                GridLayoutMode.GRID_4X4,
                GridLayoutMode.HERO_1_5,
                GridLayoutMode.HERO_1_7,
                GridLayoutMode.HERO_1_9,
                GridLayoutMode.HERO_1_11,
            ).inOrder()
        assertThat(GridLayoutMode.available(16))
            .containsExactlyElementsIn(GridLayoutMode.entries.filter { !it.isPortraitHero })
    }

    @Test
    fun `menu hides fixed grids bigger than the smallest one that fits`() {
        assertThat(GridLayoutMode.available(12)).containsNoneOf(GridLayoutMode.GRID_4X4, GridLayoutMode.HERO_1_15)
        assertThat(GridLayoutMode.available(13)).contains(GridLayoutMode.GRID_4X4)
        assertThat(GridLayoutMode.available(10)).containsAtLeast(GridLayoutMode.GRID_3X3, GridLayoutMode.GRID_4X3)
        assertThat(GridLayoutMode.available(0)).containsExactly(GridLayoutMode.AUTO, GridLayoutMode.GRID_3X3)
        assertThat(GridLayoutMode.available(40))
            .containsExactlyElementsIn(GridLayoutMode.entries.filter { !it.isPortraitHero })
    }

    @Test
    fun `stored names map to entries and the legacy hero to 1+5`() {
        assertThat(GridLayoutMode.fromStoredName("GRID_4X3")).isEqualTo(GridLayoutMode.GRID_4X3)
        assertThat(GridLayoutMode.fromStoredName("HERO")).isEqualTo(GridLayoutMode.HERO_1_5)
        assertThat(GridLayoutMode.fromStoredName("HERO_1_9")).isEqualTo(GridLayoutMode.HERO_1_9)
        assertThat(GridLayoutMode.fromStoredName("nonsense")).isNull()
        assertThat(GridLayoutMode.fromStoredName(null)).isNull()
    }
}
