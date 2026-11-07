package com.itsluminous.hikviewer.player

import android.media.MediaCodec

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
 * A flat grid leaves both hardware slots idle on purpose. Handing them to positions 0 and 1 was
 * tried in round 6 (8 % of a core per hardware tile against 22 % for software): on the MiTV the
 * `OMX.MS.AVC.Decoder` fed through the jitter buffer put its pictures out at 83 ± 60 ms (max
 * 205 ms) while the paced software tiles next to it showed 83 ± 18-24 ms, and the device's idle
 * share did not move (6-26 %, as before). Two visibly jerkier tiles for no measured headroom is
 * the wrong trade in a grid whose complaint was jitter, so the hardware decoders stay with the
 * hero and the single-camera screens, where they run unpaced.
 *
 * Focus is deliberately absent. Until round 5 the focused tile was handed a hardware decoder,
 * which swapped the tile's codec on every D-pad move: the picture went black for about a second
 * (release, create, wait for the next keyframe) on the tile the viewer had just chosen, and the
 * tile that lost focus did the same in reverse. A focus change now changes nothing in the
 * players; the hardware slots belong to the hero and to the fullscreen screen, which is where the
 * viewer looks at one picture closely. Pure function so the matrix of cases is table-tested.
 */
object DecoderPolicy {
    /**
     * Hardware decoders proven to coexist on the target TV (the third one fails with error 1100),
     * and the most the policy ever uses; [hardwareSlots] lowers it for a SoC that reports fewer.
     */
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
     * What a tile does after its decoder failed, at start or later: a hardware decoder is a
     * preference, so any failure of one (whatever the error code: 1100 "no more instances" on the
     * MiTV, but also the codes an Amlogic box returns for a surface it dislikes) is answered with a
     * software decoder; a software decoder that fails leaves nothing to fall back to, and the tile
     * shows the error with Retry. The code and the diagnostic text are logged either way and kept
     * for the detail dialog ([describeFailure]).
     */
    fun afterDecoderFailure(
        software: Boolean,
        hardwareTried: Boolean = true,
    ): FailureAction = when {
        !software -> FailureAction.RETRY_WITH_SOFTWARE
        !hardwareTried -> FailureAction.RETRY_WITH_HARDWARE
        else -> FailureAction.TILE_ERROR
    }

    /** The answers to a decoder failure. */
    enum class FailureAction {
        /** Build a software decoder at the next keyframe and leave hardware alone for a while. */
        RETRY_WITH_SOFTWARE,

        /**
         * A software decoder failed on a tile that never tried hardware (a grid tile beyond the
         * hero): try the hardware decoder once before giving up. A box whose software decoder
         * cannot take another instance may still have a hardware slot free, and a live picture
         * on hardware beats a dimmed tile.
         */
        RETRY_WITH_HARDWARE,

        /** Dim the tile with Retry; nothing is left to try. */
        TILE_ERROR,
    }

    /**
     * How many hardware decoders the policy may hand out: the codec's own
     * `getMaxSupportedInstances()` ([maxSupportedInstances], null when the platform does not say)
     * clamped to 1..[DEFAULT_MAX_HARDWARE_DECODERS]. At least one, because the hero and the
     * single-camera screens want hardware and a refusal falls back to software anyway; at most
     * two, which is all the MiTV proved and all the policy ever asks for. Unknown reads as two.
     */
    fun hardwareSlots(maxSupportedInstances: Int?): Int =
        (maxSupportedInstances ?: DEFAULT_MAX_HARDWARE_DECODERS).coerceIn(1, DEFAULT_MAX_HARDWARE_DECODERS)

    /**
     * One line about a decoder failure for the log and the detail dialog: the codec name, the
     * platform's error code with its transient/recoverable flags and `getDiagnosticInfo()` for a
     * `MediaCodec.CodecException`, or the exception class and message for anything else.
     */
    fun describeFailure(
        codecName: String,
        error: Throwable,
    ): String = if (error is MediaCodec.CodecException) {
        "codec=$codecName error=${error.errorCode} transient=${error.isTransient} " +
            "recoverable=${error.isRecoverable} diag=${error.diagnosticInfo}"
    } else {
        "codec=$codecName error=${error.javaClass.simpleName}: ${error.message}"
    }
}
