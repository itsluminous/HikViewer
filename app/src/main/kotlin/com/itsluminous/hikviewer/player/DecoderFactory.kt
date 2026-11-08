package com.itsluminous.hikviewer.player

import android.view.Surface
import com.itsluminous.hikviewer.core.stream.CodecConfig

/** Builds a tile's decoder; tests pass a recording one. */
fun interface DecoderFactory {
    /**
     * A decoder for [codecName] drawing into [surface]; [paced] asks for a jitter buffer (software
     * tiles); [maxInputSize] is the `KEY_MAX_INPUT_SIZE` the input buffers are sized to
     * ([TileDecoder.inputSizeFor] of the keyframe the decoder is built on).
     */
    fun create(
        codecName: String,
        config: CodecConfig,
        surface: Surface,
        listener: TileDecoder.Listener,
        paced: Boolean,
        maxInputSize: Int,
    ): TileDecoder

    /** Defaults. */
    companion object {
        /** The real `MediaCodec` decoder, paced when asked. */
        val DEFAULT: DecoderFactory =
            DecoderFactory { name, config, surface, listener, paced, maxInputSize ->
                TileDecoder(
                    name,
                    config,
                    surface,
                    listener,
                    maxInputSize = maxInputSize,
                    pacer = if (paced) FramePacer() else null,
                )
            }
    }
}
