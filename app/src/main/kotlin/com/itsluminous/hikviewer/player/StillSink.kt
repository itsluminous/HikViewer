package com.itsluminous.hikviewer.player

/**
 * Where a keyframe-only tile's pictures go.
 *
 * A slideshow tile does not own a decoder and, since round 3, does not lend its `SurfaceView` to
 * the shared pool either: the pool decodes each keyframe into a plain pixel buffer and the tile
 * draws it as an image. The size asked for is the tile's own, so a 16-tile grid converts 16
 * thumbnails a second, not 16 full pictures, and the surface stays free for the tile's own live
 * decoder the moment it is promoted (nothing to disconnect first).
 */
interface StillSink {
    /** Pixel width the next picture should have; 0 while the tile has no size yet (nothing is decoded). */
    val stillWidth: Int

    /** Pixel height the next picture should have; 0 while the tile has no size yet. */
    val stillHeight: Int

    /**
     * A decoded picture, [width] × [height] ARGB pixels in [pixels] (row-major, no padding).
     * Called on the pool's worker thread; [pixels] is reused for the next picture, so copy before
     * returning.
     */
    fun onStill(
        pixels: IntArray,
        width: Int,
        height: Int,
    )
}
