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
 * Hero layouts put one 2x2 tile top-left and fill the remaining cells row by row; the grid grows
 * (3x3 → 4x3 → 4x4 → 5x4) until the hero plus the other tiles fit. In portrait (phones) the
 * grid is two columns wide and as tall as needed, with the hero spanning the full width.
 */
object GridGeometry {
    /** Cells for [tileCount] tiles in [mode]. Tiles beyond the capacity are not placed. */
    fun shape(
        mode: GridLayoutMode,
        tileCount: Int,
        portrait: Boolean = false,
    ): GridShape = when {
        portrait && mode == GridLayoutMode.HERO -> heroShape(PORTRAIT_COLUMNS, portraitHeroRows(tileCount))
        portrait -> fixedShape(PORTRAIT_COLUMNS, ceilDiv(tileCount, PORTRAIT_COLUMNS).coerceAtLeast(1))
        mode == GridLayoutMode.HERO -> landscapeHero(tileCount)
        else -> mode.resolve(tileCount).let { fixedShape(it.columns, it.rows) }
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

    private fun landscapeHero(tileCount: Int): GridShape {
        val (columns, rows) =
            HERO_LANDSCAPE_SHAPES.firstOrNull { (c, r) -> c * r - HERO_CELLS + 1 >= tileCount }
                ?: HERO_LANDSCAPE_SHAPES.last()
        return heroShape(columns, rows)
    }

    private fun heroShape(
        columns: Int,
        rows: Int,
    ): GridShape {
        val cells = mutableListOf(Cell(0, 0, HERO_SPAN, HERO_SPAN))
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (row < HERO_SPAN && column < HERO_SPAN) continue
                cells += Cell(column, row)
            }
        }
        return GridShape(columns, rows, cells)
    }

    private fun portraitHeroRows(tileCount: Int): Int =
        HERO_SPAN + ceilDiv((tileCount - 1).coerceAtLeast(0), PORTRAIT_COLUMNS)

    private fun ceilDiv(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b

    private const val HERO_SPAN = 2
    private const val HERO_CELLS = HERO_SPAN * HERO_SPAN
    private const val PORTRAIT_COLUMNS = 2
    private val HERO_LANDSCAPE_SHAPES = listOf(3 to 3, 4 to 3, 4 to 4, 5 to 4)
}
