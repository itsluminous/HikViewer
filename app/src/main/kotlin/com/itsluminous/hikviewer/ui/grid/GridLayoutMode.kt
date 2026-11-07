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
 * The even entries (1 + 6, 1 + 8, …, 1 + 16) exist for phones held upright: there the grid is
 * two columns wide with a full-width hero and the small tiles two per row, so an odd N leaves
 * one cell empty and an even N fills every row. Turned to landscape, an even layout takes the
 * square grid of the odd layout one tile smaller (1 + 6 shows as 1 + 5, 1 + 14 on the 7 x 7
 * grid of a 1 + 13) and its last tile is hidden until the phone is upright again; the
 * arrangement is one list for both orientations, so nothing is lost (see
 * [com.itsluminous.hikviewer.ui.grid.GridGeometry]).
 *
 * The fixed grids come in two families. The landscape shapes (3 x 3, 4 x 3, 4 x 4) are the TV's
 * and a turned phone's; the portrait shapes ([isPortraitShape]: one column 1 x 2, 1 x 3, 1 x 4 and
 * two columns 2 x 2 up to 2 x 8) are a phone held upright, where a tile a third of the screen wide
 * is too small to watch. Each is a concrete layout with its own arrangement whatever the
 * orientation; rotating the phone draws it as its counterpart of the same capacity, so every tile
 * keeps its camera and the persisted arrangement key does not change. The mapping (columns x rows
 * as shown on screen):
 *
 * | Layout | Upright | Turned |
 * |---|---|---|
 * | 1 x 2 | 1 x 2 | 2 x 1 |
 * | 1 x 3 | 1 x 3 | 3 x 1 |
 * | 1 x 4 | 1 x 4 | 2 x 2 (nearer 16:9 tiles than 4 x 1) |
 * | 2 x N (N = 2..8) | 2 x N | N x 2 |
 * | 3 x 3 | 3 x 3 | 3 x 3 |
 * | 4 x 3 | 3 x 4 | 4 x 3 |
 * | 4 x 4 | 4 x 4 | 4 x 4 |
 *
 * Every row but 1 x 4 is a transposition, computed by [GridGeometry]; the labels name the shape
 * in its own orientation.
 *
 * [available] lists the entries worth offering for a camera count: a 1 + N layout only when all
 * of its `N + 1` tiles would show a camera and its parity suits the screen (even for a phone held
 * upright, odd otherwise), a fixed grid of the screen's family (portrait shapes upright, landscape
 * shapes otherwise) when it is not bigger than the smallest one of that family that holds every
 * camera, and AUTO always.
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
    /** Landscape: 3x3 up to 9 cameras, 4x3 up to 12, 4x4 above. Upright: the smallest portrait shape that fits. */
    AUTO(0, 0, 0, R.string.layout_auto),

    /** Fixed 3 columns x 3 rows. */
    GRID_3X3(3, 3, 0, R.string.layout_3x3),

    /** Fixed 4 columns x 3 rows. */
    GRID_4X3(4, 3, 0, R.string.layout_4x3),

    /** Fixed 4 columns x 4 rows. */
    GRID_4X4(4, 4, 0, R.string.layout_4x4),

    /** Phone upright: one column, two rows; turned it is 2 x 1. */
    GRID_1X2(1, 2, 0, R.string.layout_1x2),

    /** Phone upright: one column, three rows; turned it is 3 x 1. */
    GRID_1X3(1, 3, 0, R.string.layout_1x3),

    /** Phone upright: one column, four rows; turned it is 2 x 2. */
    GRID_1X4(1, 4, 0, R.string.layout_1x4),

    /** Phone upright: two columns, two rows; the same turned. */
    GRID_2X2(2, 2, 0, R.string.layout_2x2),

    /** Phone upright: two columns, three rows; turned it is 3 x 2. */
    GRID_2X3(2, 3, 0, R.string.layout_2x3),

    /** Phone upright: two columns, four rows; turned it is 4 x 2. */
    GRID_2X4(2, 4, 0, R.string.layout_2x4),

    /** Phone upright: two columns, five rows; turned it is 5 x 2. */
    GRID_2X5(2, 5, 0, R.string.layout_2x5),

    /** Phone upright: two columns, six rows; turned it is 6 x 2. */
    GRID_2X6(2, 6, 0, R.string.layout_2x6),

    /** Phone upright: two columns, seven rows; turned it is 7 x 2. */
    GRID_2X7(2, 7, 0, R.string.layout_2x7),

    /** Phone upright: two columns, eight rows; turned it is 8 x 2. */
    GRID_2X8(2, 8, 0, R.string.layout_2x8),

    /** Hero 2x2 on a 3x3 grid: 2 tiles right, 3 below. */
    HERO_1_5(0, 0, 5, R.string.layout_1_5),

    /** Phone portrait: hero and three rows of two; landscape shows it as 1 + 5. */
    HERO_1_6(0, 0, 6, R.string.layout_1_6),

    /** Hero 3x3 on a 4x4 grid: 3 tiles right, 4 below. */
    HERO_1_7(0, 0, 7, R.string.layout_1_7),

    /** Phone portrait: hero and four rows of two; landscape shows it as 1 + 7. */
    HERO_1_8(0, 0, 8, R.string.layout_1_8),

    /** Hero 4x4 on a 5x5 grid: 4 tiles right, 5 below. */
    HERO_1_9(0, 0, 9, R.string.layout_1_9),

    /** Phone portrait: hero and five rows of two; landscape shows it as 1 + 9. */
    HERO_1_10(0, 0, 10, R.string.layout_1_10),

    /** Hero 5x5 on a 6x6 grid: 5 tiles right, 6 below. */
    HERO_1_11(0, 0, 11, R.string.layout_1_11),

    /** Phone portrait: hero and six rows of two; landscape shows it as 1 + 11. */
    HERO_1_12(0, 0, 12, R.string.layout_1_12),

    /** Phone portrait: hero and seven rows of two; landscape shows 1 + 13 (hero 6x6 on a 7x7 grid). */
    HERO_1_14(0, 0, 14, R.string.layout_1_14),

    /** Hero 7x7 on an 8x8 grid: 7 tiles right, 8 below. */
    HERO_1_15(0, 0, 15, R.string.layout_1_15),

    /** Phone portrait: hero and eight rows of two; landscape shows it as 1 + 15. */
    HERO_1_16(0, 0, 16, R.string.layout_1_16),
    ;

    /** True for the shapes whose tile capacity is `columns * rows`. */
    val isFixed: Boolean get() = columns > 0

    /** True for the 1 + N layouts, whose position 0 is the hero. */
    val isHero: Boolean get() = heroCompanions > 0

    /** Tiles a fixed or 1 + N shape can hold; 0 for AUTO. */
    val capacity: Int get() = if (isHero) heroCompanions + 1 else columns * rows

    /** True for the even 1 + N layouts, which fill a phone's two-column portrait grid. */
    val isPortraitHero: Boolean get() = isHero && heroCompanions % 2 == 0

    /** True for the one- and two-column fixed shapes of a phone held upright (see the class table). */
    val isPortraitShape: Boolean get() = isFixed && columns <= PORTRAIT_COLUMNS

    /**
     * Cells the hero spans per side on the square landscape grid (`k`), 0 otherwise. An even
     * layout uses the grid of the odd layout one tile smaller, so 1 + 6 and 1 + 5 both give 2.
     */
    val heroSpan: Int get() = if (isHero) (heroCompanions - 1) / 2 else 0

    /** Tiles the square landscape grid holds: all of an odd layout, all but one of an even layout. */
    val landscapeCapacity: Int get() = if (isHero) 2 * heroSpan + 2 else capacity

    /**
     * Resolves AUTO to a fixed shape for [tileCount]; other modes return themselves. Turned (the
     * default), 3 x 3 up to 9 tiles, 4 x 3 up to 12 and 4 x 4 above; [portrait], the smallest
     * portrait shape that holds every tile: one column for one or two cameras, two columns from
     * three up, 2 x 8 above sixteen.
     */
    fun resolve(
        tileCount: Int,
        portrait: Boolean = false,
    ): GridLayoutMode = when {
        this != AUTO -> this
        portrait -> autoPortraitShapes.firstOrNull { it.capacity >= tileCount } ?: autoPortraitShapes.last()
        tileCount <= GRID_3X3.capacity -> GRID_3X3
        tileCount <= GRID_4X3.capacity -> GRID_4X3
        else -> GRID_4X4
    }

    /** Pickers. */
    companion object {
        /** Columns of a phone held upright; the portrait shapes have at most this many. */
        const val PORTRAIT_COLUMNS: Int = 2

        /** The 1 + N layouts in ascending size. */
        val heroModes: List<GridLayoutMode> get() = entries.filter { it.isHero }

        /** The portrait shapes AUTO picks from upright, smallest first: 1 x 2, then the two-column ones. */
        private val autoPortraitShapes: List<GridLayoutMode> =
            listOf(GRID_1X2) + entries.filter { it.isPortraitShape && it.columns == PORTRAIT_COLUMNS }

        /**
         * The entries worth offering when [cameraCount] cameras are visible, in declaration
         * order. [portraitPhone] picks the portrait shapes and the even 1 + N layouts (which fill
         * the two-column portrait grid) over the landscape shapes and the odd ones (which fill the
         * square landscape grid of a TV or a turned phone). A fixed grid is offered when it is not
         * bigger than the smallest of its family that holds every camera; two shapes of that size
         * (1 x 4 and 2 x 2 for four cameras) are both offered, since AUTO itself picks 2 x 2 there.
         */
        fun available(
            cameraCount: Int,
            portraitPhone: Boolean = false,
        ): List<GridLayoutMode> {
            val family = entries.filter { it.isFixed && it.isPortraitShape == portraitPhone }
            val largestOffered = family.firstOrNull { it.capacity >= cameraCount }?.capacity ?: Int.MAX_VALUE
            return entries.filter { mode ->
                when {
                    mode == AUTO -> true
                    mode.isHero -> mode.capacity <= cameraCount && mode.isPortraitHero == portraitPhone
                    else -> mode in family && mode.capacity <= largestOffered
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
