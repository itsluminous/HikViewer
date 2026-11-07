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
            // The shape is the layout's own: the orientation flag only matters to AUTO.
            assertThat(GridGeometry.shape(mode, tileCount = 40, portrait = true)).isEqualTo(shape)
        }
    }

    /** The even layouts are the phone's upright heroes: two columns, a full-width hero, no empty cell, whatever the flag says. */
    @Test
    fun `each even 1+N layout fills two columns with a full-width hero and no empty cell`() {
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
            val shape = GridGeometry.shape(mode, tileCount = 40, portrait = true)
            assertThat(shape.columns).isEqualTo(2)
            assertThat(shape.rows).isEqualTo(2 + n / 2)
            assertThat(shape.capacity).isEqualTo(n + 1)
            assertThat(mode.capacity).isEqualTo(n + 1)
            assertThat(mode.heroSpan).isEqualTo(0) // no square grid: the hero is the two columns' width
            assertThat(shape.cells.first()).isEqualTo(Cell(0, 0, 2, 2))
            // Every cell is a tile, apart from the three extra cells the hero spans.
            assertThat(shape.capacity).isEqualTo(shape.columns * shape.rows - 3)
            assertThat(shape.cells.last()).isEqualTo(Cell(1, shape.rows - 1)) // no empty cell
            assertThat(shape.cells.drop(1))
                .containsExactlyElementsIn(
                    (2 until shape.rows).flatMap { row -> listOf(Cell(0, row), Cell(1, row)) },
                ).inOrder()
            // An even layout is only ever the upright layout, so the shape does not depend on the flag.
            assertThat(GridGeometry.shape(mode, tileCount = 40, portrait = false)).isEqualTo(shape)
        }
        // An odd layout in two columns would leave the last cell empty (6 tiles, 7 cells), which is why phones get the even ones.
        assertThat(GridLayoutMode.HERO_1_5.capacity).isEqualTo(6)
        assertThat(2 * (2 + (5 + 1) / 2) - 3).isEqualTo(7)
    }

    @Test
    fun `1+N capacity does not depend on the tile count`() {
        assertThat(GridGeometry.shape(GridLayoutMode.HERO_1_7, 2).capacity).isEqualTo(8)
        assertThat(GridGeometry.shape(GridLayoutMode.HERO_1_7, 16).capacity).isEqualTo(8)
    }

    @Test
    fun `portrait auto uses two columns and the even heroes a full-width hero`() {
        val fixed = GridGeometry.shape(GridLayoutMode.AUTO, 5, portrait = true)
        assertThat(fixed.columns).isEqualTo(2)
        assertThat(fixed.rows).isEqualTo(3)
        assertThat(fixed.cells[4]).isEqualTo(Cell(0, 2))
        val hero = GridGeometry.shape(GridLayoutMode.HERO_1_6, 6, portrait = true)
        assertThat(hero.columns).isEqualTo(2)
        assertThat(hero.rows).isEqualTo(5)
        assertThat(hero.capacity).isEqualTo(7)
        assertThat(hero.cells.first()).isEqualTo(Cell(0, 0, 2, 2))
        assertThat(hero.cells[1]).isEqualTo(Cell(0, 2))
        assertThat(GridGeometry.shape(GridLayoutMode.HERO_1_8, 8, portrait = true).rows).isEqualTo(6)
        assertThat(GridGeometry.shape(GridLayoutMode.AUTO, 0, portrait = true)).isEqualTo(
            GridGeometry.shape(GridLayoutMode.GRID_1X2, 0, portrait = true),
        )
    }

    @Test
    fun `auto upright resolves to the smallest portrait shape that holds every tile`() {
        val expected =
            mapOf(
                0 to GridLayoutMode.GRID_1X2,
                1 to GridLayoutMode.GRID_1X2,
                2 to GridLayoutMode.GRID_1X2,
                3 to GridLayoutMode.GRID_2X2,
                4 to GridLayoutMode.GRID_2X2,
                5 to GridLayoutMode.GRID_2X3,
                6 to GridLayoutMode.GRID_2X3,
                7 to GridLayoutMode.GRID_2X4,
                8 to GridLayoutMode.GRID_2X4,
                9 to GridLayoutMode.GRID_2X5,
                12 to GridLayoutMode.GRID_2X6,
                13 to GridLayoutMode.GRID_2X7,
                15 to GridLayoutMode.GRID_2X8,
                16 to GridLayoutMode.GRID_2X8,
                40 to GridLayoutMode.GRID_2X8,
            )
        expected.forEach { (tiles, mode) ->
            assertThat(GridLayoutMode.AUTO.resolve(tiles, portrait = true)).isEqualTo(mode)
            val shape = GridGeometry.shape(GridLayoutMode.AUTO, tiles, portrait = true)
            assertThat(shape.columns).isEqualTo(mode.columns)
            assertThat(shape.rows).isEqualTo(mode.rows)
        }
        // 1 x 3 and 1 x 4 are only ever picked by hand: AUTO goes to two columns from three cameras.
        assertThat(GridLayoutMode.entries.filter { GridLayoutMode.AUTO.resolve(it.capacity, portrait = true) == it })
            .containsNoneOf(GridLayoutMode.GRID_1X3, GridLayoutMode.GRID_1X4)
        // A landscape screen never resolves AUTO to a portrait shape, nor the other way round.
        assertThat(GridLayoutMode.AUTO.resolve(5).isPortraitShape).isFalse()
        // Only AUTO resolves: every other layout is itself whatever the flag says.
        GridLayoutMode.entries.filter { it != GridLayoutMode.AUTO }.forEach { mode ->
            assertThat(mode.resolve(40, portrait = false)).isEqualTo(mode)
            assertThat(mode.resolve(40, portrait = true)).isEqualTo(mode)
        }
    }

    @Test
    fun `each portrait shape fills row by row upright and has its own columns and rows`() {
        val expected =
            mapOf(
                GridLayoutMode.GRID_1X2 to (1 to 2),
                GridLayoutMode.GRID_1X3 to (1 to 3),
                GridLayoutMode.GRID_1X4 to (1 to 4),
                GridLayoutMode.GRID_2X2 to (2 to 2),
                GridLayoutMode.GRID_2X3 to (2 to 3),
                GridLayoutMode.GRID_2X4 to (2 to 4),
                GridLayoutMode.GRID_2X5 to (2 to 5),
                GridLayoutMode.GRID_2X6 to (2 to 6),
                GridLayoutMode.GRID_2X7 to (2 to 7),
                GridLayoutMode.GRID_2X8 to (2 to 8),
            )
        assertThat(GridLayoutMode.entries.filter { it.isPortraitShape })
            .containsExactlyElementsIn(expected.keys)
            .inOrder()
        expected.forEach { (mode, dims) ->
            val (columns, rows) = dims
            assertThat(mode.isFixed).isTrue()
            assertThat(mode.isHero).isFalse()
            assertThat(mode.capacity).isEqualTo(columns * rows)
            val shape = GridGeometry.shape(mode, tileCount = 40, portrait = true)
            assertThat(shape.columns).isEqualTo(columns)
            assertThat(shape.rows).isEqualTo(rows)
            assertThat(shape.capacity).isEqualTo(mode.capacity)
            val rowMajor = (0 until columns * rows).map { Cell(it % columns, it / columns) }
            assertThat(shape.cells).containsExactlyElementsIn(rowMajor).inOrder()
            // The tile count never changes the shape: a short list leaves cells empty, a long one is cut.
            assertThat(GridGeometry.shape(mode, 1, portrait = true)).isEqualTo(shape)
        }
        assertThat(GridLayoutMode.entries.filter { it.isFixed && !it.isPortraitShape })
            .containsExactly(GridLayoutMode.GRID_3X3, GridLayoutMode.GRID_4X3, GridLayoutMode.GRID_4X4)
            .inOrder()
        // The portrait layouts (what the upright menu offers and the portrait key holds): those shapes and the even heroes.
        assertThat(GridLayoutMode.entries.filter { it.isPortrait })
            .containsExactlyElementsIn(expected.keys + GridLayoutMode.heroModes.filter { it.isPortraitHero })
        assertThat(GridLayoutMode.AUTO.isPortrait).isFalse()
    }

    /**
     * A layout belongs to one orientation and is drawn with its own columns and rows: 2 x 3 is two
     * columns and three rows, 4 x 3 four and three, whatever the flag says. There is no
     * transposition and nothing is hidden; a phone turned shows the layout stored for the other
     * orientation instead (round 17, replacing the round-16 rule that drew a stored 4 x 3 as AUTO).
     */
    @Test
    fun `a fixed shape is drawn with its own columns and rows whatever the orientation flag`() {
        GridLayoutMode.entries.filter { it.isFixed }.forEach { mode ->
            listOf(true, false).forEach { portrait ->
                val shape = GridGeometry.shape(mode, 40, portrait)
                assertThat(shape.columns).isEqualTo(mode.columns)
                assertThat(shape.rows).isEqualTo(mode.rows)
                assertThat(shape.capacity).isEqualTo(mode.capacity)
            }
        }
        assertThat(GridGeometry.shape(GridLayoutMode.GRID_2X3, 6, portrait = false).columns).isEqualTo(2)
        assertThat(GridGeometry.shape(GridLayoutMode.GRID_4X3, 12, portrait = true).columns).isEqualTo(4)
        // AUTO is the one entry the flag decides: two columns upright, the TV's grids turned.
        for (cameras in 3..16) {
            val upright = GridGeometry.shape(GridLayoutMode.AUTO, cameras, portrait = true)
            assertThat(upright.columns).isEqualTo(2)
            assertThat(upright.rows).isEqualTo((cameras + 1) / 2)
            assertThat(GridGeometry.shape(GridLayoutMode.AUTO, cameras, portrait = false).columns).isAtLeast(3)
        }
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
            assertNoOverlap(GridGeometry.rects(GridGeometry.shape(mode, 16, portrait = false), 1920, 1080, 6))
            assertNoOverlap(GridGeometry.rects(GridGeometry.shape(mode, 16, portrait = true), 1080, 1920, 6))
        }
    }

    private fun assertNoOverlap(rects: List<TileRect>) {
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val a = rects[i]
                val b = rects[j]
                val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                assertThat(overlap).isFalse()
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
                GridLayoutMode.GRID_1X2,
                GridLayoutMode.GRID_1X3,
                GridLayoutMode.GRID_1X4,
                GridLayoutMode.GRID_2X2,
                GridLayoutMode.GRID_2X3,
                GridLayoutMode.GRID_2X4,
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
            .containsExactlyElementsIn(GridLayoutMode.entries.filter { !it.isPortraitHero && !it.isPortraitShape })
    }

    /** A phone held upright never sees the TV's grids, and the TV or a turned phone never sees the portrait shapes. */
    @Test
    fun `menu offers the portrait shapes upright and the landscape shapes otherwise`() {
        assertThat(GridLayoutMode.available(2, portraitPhone = true))
            .containsExactly(GridLayoutMode.AUTO, GridLayoutMode.GRID_1X2)
            .inOrder()
        assertThat(GridLayoutMode.available(3, portraitPhone = true))
            .containsExactly(GridLayoutMode.AUTO, GridLayoutMode.GRID_1X2, GridLayoutMode.GRID_1X3)
            .inOrder()
        // Four cameras: 1 x 4 and 2 x 2 both hold exactly four, and AUTO picks 2 x 2, so both are offered.
        assertThat(GridLayoutMode.available(4, portraitPhone = true))
            .containsExactly(
                GridLayoutMode.AUTO,
                GridLayoutMode.GRID_1X2,
                GridLayoutMode.GRID_1X3,
                GridLayoutMode.GRID_1X4,
                GridLayoutMode.GRID_2X2,
            ).inOrder()
        // Whatever AUTO resolves to is a layout the menu lets the user pin by hand, in both orientations;
        // the one exception is three cameras upright, where AUTO prefers 2 x 2 (one cell empty, as before
        // this round) to the 1 x 3 stack while the menu, which never offers an empty cell, has 1 x 3.
        for (count in 0..40) {
            if (count != 3) {
                assertThat(GridLayoutMode.available(count, portraitPhone = true))
                    .contains(GridLayoutMode.AUTO.resolve(count, portrait = true))
            }
            assertThat(GridLayoutMode.available(count, portraitPhone = false))
                .contains(GridLayoutMode.AUTO.resolve(count, portrait = false))
        }
        assertThat(GridLayoutMode.AUTO.resolve(3, portrait = true)).isEqualTo(GridLayoutMode.GRID_2X2)
        assertThat(GridLayoutMode.available(3, portraitPhone = true)).doesNotContain(GridLayoutMode.GRID_2X2)
        assertThat(GridLayoutMode.available(5, portraitPhone = true))
            .containsExactly(
                GridLayoutMode.AUTO,
                GridLayoutMode.GRID_1X2,
                GridLayoutMode.GRID_1X3,
                GridLayoutMode.GRID_1X4,
                GridLayoutMode.GRID_2X2,
                GridLayoutMode.GRID_2X3,
            ).inOrder()
        // Eight cameras: every one-column shape, two columns up to 2 x 4 (which fits), and the even heroes that fill.
        assertThat(GridLayoutMode.available(8, portraitPhone = true))
            .containsExactly(
                GridLayoutMode.AUTO,
                GridLayoutMode.GRID_1X2,
                GridLayoutMode.GRID_1X3,
                GridLayoutMode.GRID_1X4,
                GridLayoutMode.GRID_2X2,
                GridLayoutMode.GRID_2X3,
                GridLayoutMode.GRID_2X4,
                GridLayoutMode.HERO_1_6,
            ).inOrder()
        assertThat(GridLayoutMode.available(16, portraitPhone = true).filter { it.isFixed })
            .containsExactlyElementsIn(GridLayoutMode.entries.filter { it.isPortraitShape })
            .inOrder()
        assertThat(GridLayoutMode.available(40, portraitPhone = true))
            .containsNoneOf(GridLayoutMode.GRID_3X3, GridLayoutMode.GRID_4X3, GridLayoutMode.GRID_4X4)
        for (count in 0..40) {
            assertThat(GridLayoutMode.available(count, portraitPhone = false).none { it.isPortraitShape }).isTrue()
            assertThat(GridLayoutMode.available(count, portraitPhone = true).none { it.isFixed && !it.isPortraitShape })
                .isTrue()
        }
    }

    @Test
    fun `menu hides fixed grids bigger than the smallest one that fits`() {
        assertThat(GridLayoutMode.available(12)).containsNoneOf(GridLayoutMode.GRID_4X4, GridLayoutMode.HERO_1_15)
        assertThat(GridLayoutMode.available(13)).contains(GridLayoutMode.GRID_4X4)
        assertThat(GridLayoutMode.available(10)).containsAtLeast(GridLayoutMode.GRID_3X3, GridLayoutMode.GRID_4X3)
        assertThat(GridLayoutMode.available(0)).containsExactly(GridLayoutMode.AUTO, GridLayoutMode.GRID_3X3)
        assertThat(GridLayoutMode.available(40))
            .containsExactlyElementsIn(GridLayoutMode.entries.filter { !it.isPortraitHero && !it.isPortraitShape })
    }

    @Test
    fun `stored names map to entries and the legacy hero to 1+5`() {
        assertThat(GridLayoutMode.fromStoredName("GRID_4X3")).isEqualTo(GridLayoutMode.GRID_4X3)
        assertThat(GridLayoutMode.fromStoredName("GRID_2X3")).isEqualTo(GridLayoutMode.GRID_2X3)
        assertThat(GridLayoutMode.fromStoredName("HERO")).isEqualTo(GridLayoutMode.HERO_1_5)
        assertThat(GridLayoutMode.fromStoredName("HERO_1_9")).isEqualTo(GridLayoutMode.HERO_1_9)
        assertThat(GridLayoutMode.fromStoredName("nonsense")).isNull()
        assertThat(GridLayoutMode.fromStoredName(null)).isNull()
    }
}
