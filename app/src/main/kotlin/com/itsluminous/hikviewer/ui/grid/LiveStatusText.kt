package com.itsluminous.hikviewer.ui.grid

import android.content.res.Resources
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.player.LivenessCounts

/**
 * The top bar's one-line summary of the grid, from the same [LivenessCounts] the dots come from:
 * "14 live" when every tile decodes at stream rate, otherwise "14 cameras · 8 live · 6 at 1 fps",
 * with the "at 1 fps" part only when some tile is in keyframe mode. Tiles with a red dot are the
 * difference between the cameras and the two counts; naming them would say "0 not live" most of
 * the time, so they are left to their dots.
 */
object LiveStatusText {
    /** The status for [counts]. */
    fun format(
        resources: Resources,
        counts: LivenessCounts,
    ): String {
        fun count(
            plural: Int,
            n: Int,
        ) = resources.getQuantityString(plural, n, n)
        if (counts.allLive) return count(R.plurals.grid_status_all_live, counts.live)
        val parts =
            ArrayList<String>(PARTS).apply {
                add(count(R.plurals.grid_status_cameras, counts.cameras))
                add(count(R.plurals.grid_status_live, counts.live))
                if (counts.atOneFps > 0) add(count(R.plurals.grid_status_at_one_fps, counts.atOneFps))
            }
        return parts.joinToString(resources.getString(R.string.grid_status_separator))
    }

    /**
     * The same counts stacked for the phone's landscape side bar, which is only as wide as its
     * icons: a number on one line and its word on the next ("12" / "live", then "4" / "1 fps" when
     * some tiles show stills). The camera total is left out, the tiles themselves show it, so no
     * line is longer than "1 fps" and the bar never has to grow for the text.
     */
    fun compact(
        resources: Resources,
        counts: LivenessCounts,
    ): String {
        val lines =
            ArrayList<String>(COMPACT_LINES).apply {
                add(counts.live.toString())
                add(resources.getString(R.string.grid_status_compact_live))
                if (counts.atOneFps > 0) {
                    add(counts.atOneFps.toString())
                    add(resources.getString(R.string.grid_status_compact_one_fps))
                }
            }
        return lines.joinToString("\n")
    }

    private const val PARTS = 3
    private const val COMPACT_LINES = 4
}
