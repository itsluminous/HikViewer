package com.itsluminous.hikviewer.player

/** How a tile decodes. */
enum class TileMode {
    /** Hardware decoder, every frame. Focused and hero tiles. */
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
 * Assigns a [TileMode] to every tile from the focus, the hero and the live budget.
 *
 * Rules, in order: the focused tile and the hero tile get the (at most [maxHardwareDecoders])
 * hardware decoders because they are the tiles the viewer is looking at; the remaining tiles are
 * live in display order until [liveLimit] is reached; everything else is keyframe-only. Pure
 * function so the matrix of cases is table-tested.
 */
object DecoderPolicy {
    /** Hardware decoders proven to coexist on the target TV (the third one fails with error 1100). */
    const val DEFAULT_MAX_HARDWARE_DECODERS: Int = 2

    /** Mode per tile index, `tileCount` entries. */
    fun assign(
        tileCount: Int,
        focusedIndex: Int?,
        heroIndex: Int?,
        liveLimit: Int,
        maxHardwareDecoders: Int = DEFAULT_MAX_HARDWARE_DECODERS,
    ): List<TileMode> {
        if (tileCount == 0) return emptyList()
        val modes = MutableList(tileCount) { TileMode.KEYFRAME }
        var live = 0
        var hardware = 0
        val priority =
            listOfNotNull(focusedIndex, heroIndex).filter { it in 0 until tileCount }.distinct() +
                (0 until tileCount)
        for (index in priority.distinct()) {
            if (live >= liveLimit) break
            val wantsHardware = index == focusedIndex || index == heroIndex
            modes[index] =
                if (wantsHardware && hardware < maxHardwareDecoders) {
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
