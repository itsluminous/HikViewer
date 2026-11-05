package com.itsluminous.hikviewer.player

import android.view.Surface
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.stream.CodecConfig
import com.itsluminous.hikviewer.core.stream.StreamQuality
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.Future

/**
 * Builds [PlaybackCoordinator]s (grids) and single [TilePlayer]s (recorded playback) for the
 * screens. The two seams that touch `MediaCodec` — codec name lookup and [TileDecoder]
 * construction — are constructor parameters so tests can run whole Activities under Robolectric
 * with recording stand-ins.
 */
class PlaybackFactory(
    private val pickCodec: (String, Boolean) -> String? = CodecNames::pick,
    private val decoderFactory: (String, CodecConfig, Surface, TileDecoder.Listener) -> TileDecoder = ::TileDecoder,
    private val poolFactory: () -> KeyframeDecoderPool = { KeyframeDecoderPool() },
    /** Where players collect their streams and feed decoders: the low-priority [StreamThreads] pool. */
    private val dispatcher: CoroutineDispatcher = StreamThreads.dispatcher,
    private val tickMillis: Long = PlaybackCoordinator.DEFAULT_TICK_MILLIS,
    /** How players tear decoders down; the recorder tests pass a synchronous one. */
    private val releaser: (TileDecoder) -> Future<*> = DecoderReleaser::release,
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
        releaser = releaser,
    )

    /**
     * One player on the hardware decoder, for a screen that shows a single stream without a
     * budget (recorded playback is 720p HEVC, which only hardware can decode). The caller owns
     * start/stop and the watchdog [TilePlayer.tick]; [tickMillis] is the suggested period.
     */
    fun createSinglePlayer(
        camera: Camera,
        opener: StreamOpener,
        quality: StreamQuality = StreamQuality.MAIN,
    ): TilePlayer = TilePlayer(
        camera = camera,
        opener = opener,
        pool = poolFactory(),
        quality = quality,
        pickCodec = pickCodec,
        decoderFactory = decoderFactory,
        dispatcher = dispatcher,
        releaser = releaser,
    ).also { it.mode = TileMode.HW_LIVE }

    /** Suggested watchdog period for players created here. */
    val watchdogMillis: Long get() = tickMillis
}
