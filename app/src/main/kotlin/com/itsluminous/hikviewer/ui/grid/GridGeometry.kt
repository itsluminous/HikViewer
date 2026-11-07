package com.itsluminous.hikviewer.ui.grid

/** A tile's cell span in grid units: column/row of its top-left cell and its size in cells. */
data class Cell(
    val column: Int,
    val row: Int,
    val columnSpan: Int = 1,
    val rowSpan: Int = 1,
)

/** Pixel rectangle; a plain class so geometry tests run without Android. */
data class TileRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** The resolved shape of the grid: how many cells, and where each tile sits. */
data class GridShape(
    val columns: Int,
    val rows: Int,
    /** One entry per tile, in tile order. */
    val cells: List<Cell>,
) {
    /** How many tiles fit. */
    val capacity: Int get() = cells.size
}

/**
 * Pure layout maths for [CameraGridLayout]: which tile goes in which cell, and where a cell is in
 * pixels. Separated from the `ViewGroup` so every layout can be asserted on the JVM.
 *
 * A fixed grid is drawn with its own columns and rows on a screen of its orientation, and turned
 * as the counterpart of the same capacity (see [dimensions] and the table on [GridLayoutMode]):
 * a turned phone shows 2 x 3 as 3 x 2 and 1 x 4 as 2 x 2. In portrait AUTO resolves to the
 * smallest portrait shape that holds every tile (two columns from three cameras), in landscape
 * to 3 x 3, 4 x 3 or 4 x 4; a landscape shape stored on a phone held upright is drawn as AUTO
 * ([GridLayoutMode.effective]), so the grid upright is never wider than two columns.
 *
 * A 1 + N layout ([GridLayoutMode.isHero]) is a square `(k+1) x (k+1)` grid whose hero spans the
 * top-left `k x k` cells; the small tiles fill the right column top to bottom, then the bottom
 * row left to right (row-major order, like the fixed grids). In portrait (phones) the grid is two
 * columns wide and as tall as needed, with the hero spanning the full width (2 x 2 cells) and
 * the small tiles two per row beneath it.
 *
 * The two shapes of a 1 + N layout hold different counts, and the shape decides what is shown:
 * an odd N fills the square grid and leaves one portrait cell empty; an even N fills the portrait
 * rows and, in landscape, gets the square grid of the odd layout one tile smaller, so its last
 * tile (the last position of the arrangement) has no cell and is hidden until the phone is
 * upright again. Both orientations read the same arrangement, so the hidden tile keeps its place.
 */
object GridGeometry {
    /** Cells for [tileCount] tiles in [mode]. Tiles beyond the shape's capacity are not placed. */
    fun shape(
        mode: GridLayoutMode,
        tileCount: Int,
        portrait: Boolean = false,
    ): GridShape = when {
        portrait && mode.isHero -> portraitHero(mode)
        mode.isHero -> heroShape(mode.heroSpan + 1, mode.heroSpan + 1, mode.heroSpan)
        else -> {
            val (columns, rows) = dimensions(mode.resolve(tileCount, portrait), portrait)
            fixedShape(columns, rows)
        }
    }

    /**
     * Columns and rows the fixed [mode] is drawn with on a screen that is [portrait] or not: its
     * own when the shape belongs to that orientation ([GridLayoutMode.isPortraitShape]), otherwise
     * the transposition, except that 1 x 4 turned is 2 x 2 (four tiles of 16:9 instead of a row of
     * four slivers). The capacity never changes, so the arrangement is one list for both. A
     * landscape shape is never asked for upright: [shape] resolves it to AUTO there first.
     */
    fun dimensions(
        mode: GridLayoutMode,
        portrait: Boolean,
    ): Pair<Int, Int> {
        require(mode.isFixed) { "$mode is not a fixed grid" }
        require(mode.isPortraitShape || !portrait) { "$mode is drawn as AUTO upright" }
        return when {
            mode.isPortraitShape == portrait -> mode.columns to mode.rows
            mode == GridLayoutMode.GRID_1X4 -> 2 to 2
            else -> mode.rows to mode.columns
        }
    }

    /** Pixel rectangles for [shape] inside a `width` x `height` area with [gap] pixels between cells. */
    fun rects(
        shape: GridShape,
        width: Int,
        height: Int,
        gap: Int,
    ): List<TileRect> {
        val cellWidth = (width - gap * (shape.columns - 1)) / shape.columns.coerceAtLeast(1)
        val cellHeight = (height - gap * (shape.rows - 1)) / shape.rows.coerceAtLeast(1)
        return shape.cells.map { c ->
            val left = c.column * (cellWidth + gap)
            val top = c.row * (cellHeight + gap)
            TileRect(
                left = left,
                top = top,
                right = left + cellWidth * c.columnSpan + gap * (c.columnSpan - 1),
                bottom = top + cellHeight * c.rowSpan + gap * (c.rowSpan - 1),
            )
        }
    }

    private fun fixedShape(
        columns: Int,
        rows: Int,
    ): GridShape = GridShape(columns, rows, (0 until columns * rows).map { Cell(it % columns, it / columns) })

    /** Two columns, full-width hero, companions two per row; an odd N leaves the last cell empty, an even N none. */
    private fun portraitHero(mode: GridLayoutMode): GridShape {
        val rows = PORTRAIT_HERO_SPAN + ceilDiv(mode.heroCompanions, PORTRAIT_COLUMNS)
        val shape = heroShape(PORTRAIT_COLUMNS, rows, PORTRAIT_HERO_SPAN)
        return shape.copy(cells = shape.cells.take(mode.capacity))
    }

    /** Hero of [heroSpan] x [heroSpan] cells top-left; the other cells row by row. */
    private fun heroShape(
        columns: Int,
        rows: Int,
        heroSpan: Int,
    ): GridShape {
        val cells = mutableListOf(Cell(0, 0, heroSpan, heroSpan))
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (row < heroSpan && column < heroSpan) continue
                cells += Cell(column, row)
            }
        }
        return GridShape(columns, rows, cells)
    }

    private fun ceilDiv(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b

    private const val PORTRAIT_COLUMNS = GridLayoutMode.PORTRAIT_COLUMNS
    private const val PORTRAIT_HERO_SPAN = 2
}
