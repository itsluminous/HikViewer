package com.itsluminous.hikviewer.player

/**
 * Lines the live tiles' pictures up on a shared display cadence.
 *
 * SurfaceFlinger composites a frame whenever any layer has a new buffer. Eight live tiles at
 * 12 fps with uncorrelated frame phases hand it a new buffer on almost every 60 Hz vsync, and on
 * the MiTV all of those tiles are GLES-composited (the vendor composer gives this grid one
 * hardware overlay), so the GPU recomposes the whole screen ~60 times a second for 96 pictures.
 * Measured with the grid streaming: the app's own frames spent 15-45 ms in their GPU stage for
 * half a millisecond of UI work, and the focus border lagged the remote.
 *
 * Presenting every software tile's picture at the next multiple of [DEFAULT_SLOT_NANOS] on the
 * monotonic clock makes the tiles' buffers due in the same vsync, so one composition shows them
 * all: at most 15 compositions a second instead of 60, for an added display latency of 0-67 ms
 * (33 ms on average) that a surveillance view does not notice. A camera faster than 15 fps is
 * shown at 15 fps in a tile (SurfaceFlinger keeps the newest buffer due in a slot); the hero and
 * the fullscreen screen render immediately (slot 0) and keep every frame.
 */
object RenderPacer {
    /** 1/15 s: four 60 Hz vsyncs; the recorder sub-stream's 12 fps always finds a free slot. */
    const val DEFAULT_SLOT_NANOS: Long = 1_000_000_000L / 15

    /** The time to present a picture decoded at [nowNanos]: the next slot boundary, or now when [slotNanos] is 0. */
    fun presentAt(
        nowNanos: Long,
        slotNanos: Long,
    ): Long = if (slotNanos <= 0L) nowNanos else (nowNanos / slotNanos + 1) * slotNanos
}
