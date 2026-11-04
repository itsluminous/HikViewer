package com.itsluminous.hikviewer.fakes

import android.media.MediaCodec
import android.view.Surface
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.CodecConfig
import com.itsluminous.hikviewer.player.KeyframeDecoderPool
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.player.TileDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [TileDecoder] that never opens a codec: records what it was fed and lets a test drive the
 * listener (first frame, errors) and the stall flag by hand.
 */
class RecordingDecoder(
    codecName: String,
    config: CodecConfig,
    surface: Surface,
    val listener: TileDecoder.Listener,
    private val failOnStart: MediaCodec.CodecException? = null,
) : TileDecoder(codecName, config, surface, listener) {
    /** Every access unit fed. */
    val fed = CopyOnWriteArrayList<AccessUnit>()

    /** True after [start]. */
    var started = false
        private set

    /** True after [release]. */
    var released = false
        private set

    /** What [checkStall] answers. */
    var stalled = false

    /** What [queuedFrames] answers. */
    var queued = 0

    override val queuedFrames: Int get() = queued

    override fun start() {
        failOnStart?.let { throw it }
        started = true
    }

    override fun feed(unit: AccessUnit) {
        fed += unit
    }

    override fun checkStall(nowMillis: Long): Boolean = stalled

    override fun release() {
        released = true
    }

    /** Simulates the codec's first rendered picture. */
    fun emitFirstFrame() = listener.onFirstFrame(this)
}

/** Builds [RecordingDecoder]s and keeps them for assertions. */
class RecordingDecoderFactory {
    /** Decoders created so far, in order. */
    val created = CopyOnWriteArrayList<RecordingDecoder>()

    /** Codec names that must fail at start with the given exception, consumed in order. */
    val startFailures = ArrayDeque<Pair<String, MediaCodec.CodecException>>()

    /** The factory lambda for [TileDecoder]'s owners. */
    val factory: (
        String,
        CodecConfig,
        Surface,
        TileDecoder.Listener,
    ) -> TileDecoder = { name, config, surface, listener ->
        val failure = startFailures.firstOrNull { it.first == name }?.also(startFailures::remove)?.second
        RecordingDecoder(name, config, surface, listener, failure).also(created::add)
    }

    /** `pickCodec` stand-in: `sw`/`hw` names so tests can tell which path was taken. */
    fun pickCodec(
        mimeType: String,
        software: Boolean,
    ): String = if (software) "fake.sw.$mimeType" else "fake.hw.$mimeType"

    /** The pools handed out. */
    val pools = CopyOnWriteArrayList<NoopKeyframePool>()

    /** A [PlaybackFactory] wired to this recorder and a pool that never decodes. */
    fun playbackFactory(dispatcher: CoroutineDispatcher = Dispatchers.Default): PlaybackFactory = PlaybackFactory(
        pickCodec = ::pickCodec,
        decoderFactory = factory,
        poolFactory = { NoopKeyframePool().also(pools::add) },
        dispatcher = dispatcher,
        tickMillis = 50,
    )
}

/** A pool that records submissions instead of decoding. */
class NoopKeyframePool : KeyframeDecoderPool(maxDecoders = 1, pickCodec = { null }) {
    /** Keyframes submitted, by tile. */
    val submitted = CopyOnWriteArrayList<Pair<Any, AccessUnit>>()

    override fun submit(
        tile: Any,
        surface: Surface,
        unit: AccessUnit,
    ) {
        submitted += tile to unit
    }
}
