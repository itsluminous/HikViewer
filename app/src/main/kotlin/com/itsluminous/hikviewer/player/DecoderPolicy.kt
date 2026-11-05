package com.itsluminous.hikviewer.player

/** How a tile decodes. */
enum class TileMode {
    /** Hardware decoder, every frame. The hero tile and single-camera screens. */
    HW_LIVE,

    /** Software decoder, every frame. */
    SW_LIVE,

    /** Shared pool decodes one keyframe per second. */
    KEYFRAME,
    ;

    /** True when every frame is decoded. */
    val isLive: Boolean get() = this != KEYFRAME
}

/**
 * Assigns a [TileMode] to every tile from the hero and the live budget.
 *
 * Rules, in order: the hero tile (the 2x tile of a 1 + N layout, or the only tile of a
 * single-camera screen) gets a hardware decoder; the remaining tiles are live in display order
 * until [liveLimit] is reached; everything else is keyframe-only. With [hardwareOnly] the budget
 * is ignored and only the hero decodes live: the grid asks for that while a tile is being moved,
 * when the remote must answer before the pictures do.
 *
 * Focus is deliberately absent. Until round 5 the focused tile was handed a hardware decoder,
 * which swapped the tile's codec on every D-pad move: the picture went black for about a second
 * (release, create, wait for the next keyframe) on the tile the viewer had just chosen, and the
 * tile that lost focus did the same in reverse. A focus change now changes nothing in the
 * players; the hardware slots belong to the hero and to the fullscreen screen, which is where the
 * viewer looks at one picture closely. Pure function so the matrix of cases is table-tested.
 */
object DecoderPolicy {
    /** Hardware decoders proven to coexist on the target TV (the third one fails with error 1100). */
    const val DEFAULT_MAX_HARDWARE_DECODERS: Int = 2

    /** Mode per tile index, `tileCount` entries. */
    fun assign(
        tileCount: Int,
        heroIndex: Int?,
        liveLimit: Int,
        maxHardwareDecoders: Int = DEFAULT_MAX_HARDWARE_DECODERS,
        hardwareOnly: Boolean = false,
    ): List<TileMode> {
        if (tileCount == 0) return emptyList()
        val modes = MutableList(tileCount) { TileMode.KEYFRAME }
        val hero = heroIndex?.takeIf { it in 0 until tileCount }
        var live = 0
        var hardware = 0
        val priority = listOfNotNull(hero) + (0 until tileCount).filter { it != hero }
        for (index in priority) {
            val wantsHardware = index == hero && hardware < maxHardwareDecoders
            if (hardwareOnly && !wantsHardware) continue
            if (live >= liveLimit) break
            modes[index] =
                if (wantsHardware) {
                    hardware++
                    TileMode.HW_LIVE
                } else {
                    TileMode.SW_LIVE
                }
            live++
        }
        return modes
    }

    /**
     * True when a codec failure should be answered by retrying with a software decoder: the
     * platform marks "no more instances" as transient and the target TV reports it as error 1100.
     */
    fun shouldFallBackToSoftware(
        isTransient: Boolean,
        errorCode: Int,
    ): Boolean = isTransient || errorCode == INSUFFICIENT_RESOURCE_ERROR

    /** `MediaCodec.CodecException.errorCode` the MiTV decoder returns for a third HW instance. */
    const val INSUFFICIENT_RESOURCE_ERROR: Int = 1100
}
