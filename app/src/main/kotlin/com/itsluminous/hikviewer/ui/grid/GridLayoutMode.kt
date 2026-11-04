package com.itsluminous.hikviewer.ui.grid

import androidx.annotation.StringRes
import com.itsluminous.hikviewer.R

/** The grid shapes the user can pick. [AUTO] resolves to a fixed shape by tile count. */
enum class GridLayoutMode(
    /** Columns of the fixed shape; 0 for AUTO and HERO (computed). */
    val columns: Int,
    /** Rows of the fixed shape; 0 for AUTO and HERO. */
    val rows: Int,
    /** Label for pickers. */
    @StringRes val labelRes: Int,
) {
    /** 3x3 up to 9 cameras, 4x3 up to 12, 4x4 above. */
    AUTO(0, 0, R.string.layout_auto),

    /** Fixed 3 columns x 3 rows. */
    GRID_3X3(3, 3, R.string.layout_3x3),

    /** Fixed 4 columns x 3 rows. */
    GRID_4X3(4, 3, R.string.layout_4x3),

    /** Fixed 4 columns x 4 rows. */
    GRID_4X4(4, 4, R.string.layout_4x4),

    /** One 2x2 hero tile top-left, the rest fill to its right and below. */
    HERO(0, 0, R.string.layout_hero),
    ;

    /** True for the shapes whose tile capacity is `columns * rows`. */
    val isFixed: Boolean get() = columns > 0

    /** Resolves AUTO to a fixed shape for [tileCount]; other modes return themselves. */
    fun resolve(tileCount: Int): GridLayoutMode = when {
        this != AUTO -> this
        tileCount <= GRID_3X3.capacity -> GRID_3X3
        tileCount <= GRID_4X3.capacity -> GRID_4X3
        else -> GRID_4X4
    }

    /** Tiles a fixed shape can hold. */
    val capacity: Int get() = columns * rows
}
