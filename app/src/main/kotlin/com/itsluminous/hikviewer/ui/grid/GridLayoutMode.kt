package com.itsluminous.hikviewer.ui.grid

import androidx.annotation.StringRes
import com.itsluminous.hikviewer.R

/**
 * The grid shapes the user can pick. [AUTO] resolves to a fixed shape by tile count.
 *
 * The `HERO_1_N` entries are the "1 + N" layouts: one big hero tile top-left, a column of small
 * tiles down its right and a row of small tiles under it, on a square `(k+1) x (k+1)` cell grid
 * with the hero spanning `k x k` cells, so `N = 2k + 1`. Every tile keeps the screen's aspect
 * ratio (16:9 on a TV) and the hero is `k` times a small tile: twice in 1 + 5, three times in
 * 1 + 7, and so on. The user's list (5, 7, 9, 11, 15) is exactly this family; a 2x2 hero on a
 * uniform grid can only give 5, 8, 11, 12, 14 or 16 small tiles, which is why "1 + 7" and
 * "1 + 9" are not 2x heroes. Column counts: 1 + 5 → 3, 1 + 7 → 4, 1 + 9 → 5, 1 + 11 → 6, 1 + 15 → 8.
 *
 * [available] lists the entries worth offering for a camera count: a 1 + N layout only when all
 * of its `N + 1` tiles would show a camera, a fixed grid when it is not bigger than the smallest
 * one that holds every camera, and AUTO always.
 */
enum class GridLayoutMode(
    /** Columns of the fixed shape; 0 for AUTO and the 1 + N layouts (computed). */
    val columns: Int,
    /** Rows of the fixed shape; 0 for AUTO and the 1 + N layouts. */
    val rows: Int,
    /** Small tiles next to the hero in a 1 + N layout; 0 otherwise. */
    val heroCompanions: Int,
    /** Label for pickers. */
    @StringRes val labelRes: Int,
) {
    /** 3x3 up to 9 cameras, 4x3 up to 12, 4x4 above. */
    AUTO(0, 0, 0, R.string.layout_auto),

    /** Fixed 3 columns x 3 rows. */
    GRID_3X3(3, 3, 0, R.string.layout_3x3),

    /** Fixed 4 columns x 3 rows. */
    GRID_4X3(4, 3, 0, R.string.layout_4x3),

    /** Fixed 4 columns x 4 rows. */
    GRID_4X4(4, 4, 0, R.string.layout_4x4),

    /** Hero 2x2 on a 3x3 grid: 2 tiles right, 3 below. */
    HERO_1_5(0, 0, 5, R.string.layout_1_5),

    /** Hero 3x3 on a 4x4 grid: 3 tiles right, 4 below. */
    HERO_1_7(0, 0, 7, R.string.layout_1_7),

    /** Hero 4x4 on a 5x5 grid: 4 tiles right, 5 below. */
    HERO_1_9(0, 0, 9, R.string.layout_1_9),

    /** Hero 5x5 on a 6x6 grid: 5 tiles right, 6 below. */
    HERO_1_11(0, 0, 11, R.string.layout_1_11),

    /** Hero 7x7 on an 8x8 grid: 7 tiles right, 8 below. */
    HERO_1_15(0, 0, 15, R.string.layout_1_15),
    ;

    /** True for the shapes whose tile capacity is `columns * rows`. */
    val isFixed: Boolean get() = columns > 0

    /** True for the 1 + N layouts, whose position 0 is the hero. */
    val isHero: Boolean get() = heroCompanions > 0

    /** Tiles a fixed or 1 + N shape can hold; 0 for AUTO. */
    val capacity: Int get() = if (isHero) heroCompanions + 1 else columns * rows

    /** Cells the hero spans per side in a 1 + N layout (`k`), 0 otherwise. */
    val heroSpan: Int get() = if (isHero) (heroCompanions - 1) / 2 else 0

    /** Resolves AUTO to a fixed shape for [tileCount]; other modes return themselves. */
    fun resolve(tileCount: Int): GridLayoutMode = when {
        this != AUTO -> this
        tileCount <= GRID_3X3.capacity -> GRID_3X3
        tileCount <= GRID_4X3.capacity -> GRID_4X3
        else -> GRID_4X4
    }

    /** Pickers. */
    companion object {
        /** The 1 + N layouts in ascending size. */
        val heroModes: List<GridLayoutMode> get() = entries.filter { it.isHero }

        /** The entries worth offering when [cameraCount] cameras are visible, in declaration order. */
        fun available(cameraCount: Int): List<GridLayoutMode> {
            val smallestFit = entries.filter { it.isFixed }.firstOrNull { it.capacity >= cameraCount }
            return entries.filter { mode ->
                when {
                    mode == AUTO -> true
                    mode.isHero -> mode.capacity <= cameraCount
                    else -> mode.capacity < cameraCount || mode == smallestFit
                }
            }
        }

        /**
         * The layout the stored name [name] means: a current entry, or the single "HERO" entry of
         * earlier versions mapped to [HERO_1_5] (the one with a 2x hero, as the old layout had);
         * null for anything else.
         */
        fun fromStoredName(name: String?): GridLayoutMode? = when (name) {
            null -> null
            LEGACY_HERO_NAME -> HERO_1_5
            else -> entries.firstOrNull { it.name == name }
        }

        /** Name the single hero layout was stored under before the 1 + N entries existed. */
        const val LEGACY_HERO_NAME: String = "HERO"
    }
}
