package com.itsluminous.hikviewer.ui.grid

import androidx.annotation.StringRes
import com.itsluminous.hikviewer.R

/**
 * Entries of the tile long-press menu, in menu order. The grid shows every entry and routes the
 * pick to `GridActivity.onTileAction`; a new capability (recorded playback) is one more constant
 * plus one more `when` branch there.
 */
enum class TileAction(
    /** Menu label. */
    @StringRes val labelRes: Int,
) {
    /** Pick the tile up and swap it with its neighbours. */
    MOVE(R.string.tile_action_move),

    /** Open the camera's recorded footage. */
    PLAYBACK(R.string.tile_action_playback),
}
