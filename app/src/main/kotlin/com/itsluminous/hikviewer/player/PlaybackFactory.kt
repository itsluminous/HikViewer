package com.itsluminous.hikviewer.player

import android.view.Surface
import com.itsluminous.hikviewer.core.stream.CodecConfig
import com.itsluminous.hikviewer.core.stream.StreamQuality
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Builds [PlaybackCoordinator]s for the screens. The two seams that touch `MediaCodec` — codec
 * name lookup and [TileDecoder] construction — are constructor parameters so tests can run whole
 * Activities under Robolectric with recording stand-ins.
 */
class PlaybackFactory(
    private val pickCodec: (String, Boolean) -> String? = CodecNames::pick,
    private val decoderFactory: (String, CodecConfig, Surface, TileDecoder.Listener) -> TileDecoder = ::TileDecoder,
    private val poolFactory: () -> KeyframeDecoderPool = { KeyframeDecoderPool() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val tickMillis: Long = PlaybackCoordinator.DEFAULT_TICK_MILLIS,
) {
    /** A coordinator for [opener] on [scope]. */
    fun create(
        opener: StreamOpener,
        capabilities: DeviceCapabilities,
        scope: CoroutineScope,
        quality: StreamQuality = StreamQuality.SUB,
    ): PlaybackCoordinator = PlaybackCoordinator(
        opener = opener,
        capabilities = capabilities,
        scope = scope,
        quality = quality,
        pool = poolFactory(),
        pickCodec = pickCodec,
        decoderFactory = decoderFactory,
        tickMillis = tickMillis,
        dispatcher = dispatcher,
    )
}
