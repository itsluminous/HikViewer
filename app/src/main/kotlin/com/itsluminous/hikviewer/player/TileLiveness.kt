package com.itsluminous.hikviewer.player

/**
 * Whether a tile is showing pictures, and how many: the one definition behind the green/red dot
 * and the top bar's "N live · M at 1 fps". Until round 6 the dot was green for any tile with a
 * recent picture while the status counted only [TileState.Live] tiles, so a 14-tile grid showed
 * 14 green dots over "8 live".
 */
enum class TileLiveness {
    /** Pictures reach the screen at the stream's rate: a live decoder with a recent output. */
    LIVE,

    /** The tile is in keyframe mode and a still arrived recently: honest one-picture-a-second. */
    AT_ONE_FPS,

    /** Nothing recent: connecting, stalled, failed or stopped. The red dot. */
    NOT_LIVE,
    ;

    /** True for both picture-showing states; the dot is green. */
    val isFlowing: Boolean get() = this != NOT_LIVE

    /** Rules and the counting helper. */
    companion object {
        /** A picture younger than this means pictures are flowing. */
        const val FLOWING_MILLIS: Long = 2_000

        /**
         * [mode] is how the tile decodes; [lastPictureAgeMillis] is how long ago the last picture
         * reached the screen (`Long.MAX_VALUE` when none has, or the stream is not running).
         */
        fun of(
            mode: TileMode,
            lastPictureAgeMillis: Long,
        ): TileLiveness = when {
            lastPictureAgeMillis >= FLOWING_MILLIS -> NOT_LIVE
            mode.isLive -> LIVE
            else -> AT_ONE_FPS
        }

        /** Tallies [tiles] for the status text. */
        fun count(tiles: Iterable<TileLiveness>): LivenessCounts {
            var live = 0
            var atOneFps = 0
            var total = 0
            for (t in tiles) {
                total++
                when (t) {
                    LIVE -> live++
                    AT_ONE_FPS -> atOneFps++
                    NOT_LIVE -> Unit
                }
            }
            return LivenessCounts(total, live, atOneFps)
        }
    }
}

/** How many of a grid's tiles are in each [TileLiveness] state. */
data class LivenessCounts(
    /** Tiles on the grid. */
    val cameras: Int,
    /** Tiles at stream rate. */
    val live: Int,
    /** Tiles showing one still a second. */
    val atOneFps: Int,
) {
    /** Tiles with a red dot. */
    val notLive: Int get() = cameras - live - atOneFps

    /** True when every tile is live, which is when the status needs only one number. */
    val allLive: Boolean get() = cameras > 0 && live == cameras
}
