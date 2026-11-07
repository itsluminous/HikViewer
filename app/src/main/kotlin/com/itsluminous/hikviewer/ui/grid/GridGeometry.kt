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
 * The mode decides the shape; the orientation only decides what AUTO means. A fixed grid is drawn
 * with its own columns and rows (a phone keeps a layout per orientation, so a portrait shape is
 * only ever asked for upright and a landscape shape only turned, see [GridLayoutMode]). AUTO
 * resolves upright to the smallest portrait shape that holds every tile (two columns from three
 * cameras) and turned to 3 x 3, 4 x 3 or 4 x 4.
 *
 * An odd 1 + N layout is a square `(k+1) x (k+1)` grid whose hero spans the top-left `k x k`
 * cells; the small tiles fill the right column top to bottom, then the bottom row left to right
 * (row-major order, like the fixed grids). An even 1 + N layout ([GridLayoutMode.isPortraitHero])
 * is the phone's upright shape: two columns and as many rows as needed, the hero spanning the
 * full width (2 x 2 cells) and the small tiles two per row beneath it, so no cell is empty.
 */
object GridGeometry {
    /** Cells for [tileCount] tiles in [mode]. Tiles beyond the shape's capacity are not placed. */
    fun shape(
        mode: GridLayoutMode,
        tileCount: Int,
        portrait: Boolean = false,
    ): GridShape = when {
        mode.isPortraitHero -> portraitHero(mode)
        mode.isHero -> heroShape(mode.heroSpan + 1, mode.heroSpan + 1, mode.heroSpan)
        else -> {
            val fixed = mode.resolve(tileCount, portrait)
            fixedShape(fixed.columns, fixed.rows)
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

    /** Two columns, a full-width hero and the companions two per row: an even N fills every cell. */
    private fun portraitHero(mode: GridLayoutMode): GridShape =
        heroShape(PORTRAIT_COLUMNS, PORTRAIT_HERO_SPAN + mode.heroCompanions / PORTRAIT_COLUMNS, PORTRAIT_HERO_SPAN)

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

    private const val PORTRAIT_COLUMNS = GridLayoutMode.PORTRAIT_COLUMNS
    private const val PORTRAIT_HERO_SPAN = 2
}
