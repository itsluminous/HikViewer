package com.itsluminous.hikviewer.player

import android.view.Surface
import com.itsluminous.hikviewer.core.stream.CodecConfig

/** Builds a tile's decoder; tests pass a recording one. */
fun interface DecoderFactory {
    /** A decoder for [codecName] drawing into [surface]; [paced] asks for a jitter buffer (software tiles). */
    fun create(
        codecName: String,
        config: CodecConfig,
        surface: Surface,
        listener: TileDecoder.Listener,
        paced: Boolean,
    ): TileDecoder

    /** Defaults. */
    companion object {
        /** The real `MediaCodec` decoder, paced when asked. */
        val DEFAULT: DecoderFactory =
            DecoderFactory { name, config, surface, listener, paced ->
                TileDecoder(name, config, surface, listener, pacer = if (paced) FramePacer() else null)
            }
    }
}
