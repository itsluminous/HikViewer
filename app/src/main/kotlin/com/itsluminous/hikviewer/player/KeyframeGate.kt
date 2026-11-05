package com.itsluminous.hikviewer.player

import com.itsluminous.hikviewer.core.stream.AccessUnit

/**
 * Decides which pictures may reach a decoder: nothing but an IRAP (IDR/CRA) until one has been
 * admitted, and, in [keyframesOnly] mode, nothing but IRAPs at all.
 *
 * A decoder that is handed a P-frame without the picture it predicts from shows what the hardware
 * has in its reference buffers: grey or green blocks on most SoCs, a solid green picture on some.
 * That is the state a decoder is in when it starts, after a `flush()` (a seek or speed change
 * drops everything it held), after a stream reconnect, and after a window of recorded footage was
 * closed and another opened somewhere else in the day. All of those call [await], and every
 * picture then goes through [admits] until the next keyframe. Reverse playback uses
 * [keyframesOnly]: the hops go backwards, so a P-frame's reference is a picture the decoder will
 * never see; each hop shows its keyframe and nothing else. Pure, not thread-safe (the decoder
 * calls it under its own lock).
 */
class KeyframeGate {
    /**
     * Admit IRAPs only. Switching it off re-arms the gate: the P-frames that follow predict from
     * pictures that were never decoded.
     */
    var keyframesOnly: Boolean = false
        set(value) {
            if (field && !value) awaiting = true
            field = value
        }

    private var awaiting = true

    /** True while non-IRAP pictures are being dropped. */
    val isAwaiting: Boolean get() = awaiting

    /** Non-IRAP pictures dropped so far (diagnostics). */
    var dropped: Int = 0
        private set

    /** Keyframes admitted so far (diagnostics). */
    var keyframes: Int = 0
        private set

    /** Drop everything until the next keyframe. */
    fun await() {
        awaiting = true
    }

    /** True when [unit] may be handed to the decoder. */
    fun admits(unit: AccessUnit): Boolean {
        if (unit.isKeyframe) {
            awaiting = false
            keyframes++
            return true
        }
        if (keyframesOnly || awaiting) {
            dropped++
            return false
        }
        return true
    }

    /** One token for the diagnostics line. */
    fun describe(): String {
        val state = if (awaiting) "awaiting" else "open"
        val only = if (keyframesOnly) "/keyframesOnly" else ""
        return "gate=$state$only keyframes=$keyframes gateDropped=$dropped"
    }
}
