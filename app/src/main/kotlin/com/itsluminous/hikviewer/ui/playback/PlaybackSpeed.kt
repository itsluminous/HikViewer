package com.itsluminous.hikviewer.ui.playback

/**
 * The speed button's positions, in cycling order. Negative factors step backwards.
 *
 * Only 1x is true playback: the relay streams a window in real time, so every other speed is
 * implemented as hopping ([PlaybackViewModel.HOP_MILLIS]) between short windows opened
 * `factor * HOP_MILLIS` apart and showing their first pictures — fast forward and reverse are both
 * keyframe stepping, which the help line says.
 */
enum class PlaybackSpeed(
    /** Signed multiple of real time. */
    val factor: Int,
) {
    /** Reverse, ten times. */
    REVERSE_10(-10),

    /** Reverse, five times. */
    REVERSE_5(-5),

    /** Reverse, twice. */
    REVERSE_2(-2),

    /** Real time: the relay stream as it comes. */
    NORMAL(1),

    /** Forward, twice. */
    FORWARD_2(2),

    /** Forward, five times. */
    FORWARD_5(5),

    /** Forward, ten times. */
    FORWARD_10(10),
    ;

    /** True for 1x: pictures flow continuously. */
    val isRealTime: Boolean get() = this == NORMAL

    /** Button label: `1x`, `+2x`, `-10x`. */
    val label: String get() =
        when {
            factor > 1 -> "+${factor}x"
            else -> "${factor}x"
        }

    /** The next position of the cycle (`+10x` wraps to `-10x`). */
    fun next(): PlaybackSpeed = entries[(ordinal + 1) % entries.size]

    /** The previous position of the cycle. */
    fun previous(): PlaybackSpeed = entries[(ordinal + entries.size - 1) % entries.size]
}
