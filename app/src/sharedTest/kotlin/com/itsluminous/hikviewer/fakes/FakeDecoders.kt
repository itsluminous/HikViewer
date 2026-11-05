package com.itsluminous.hikviewer.fakes

import android.view.Surface
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.CodecConfig
import com.itsluminous.hikviewer.player.DecoderFactory
import com.itsluminous.hikviewer.player.KeyframeDecoderPool
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.player.StillSink
import com.itsluminous.hikviewer.player.TileDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CompletableFuture
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
    private val failOnStart: Throwable? = null,
    /** Whether the owner asked for a jitter buffer (software tiles do, the hardware hero does not). */
    val pacedRequested: Boolean = false,
) : TileDecoder(codecName, config, surface, listener) {
    /** Every access unit fed. */
    val fed = CopyOnWriteArrayList<AccessUnit>()

    /** True after [start]. */
    var started = false
        private set

    /** True after [release]. */
    var released = false
        private set

    /** The thread [release] ran on. */
    var releasedOn: Thread? = null
        private set

    /** What [checkStall] answers. */
    var stalled = false

    /** Times the owner flushed this decoder instead of replacing it (a seek, a speed change). */
    var flushes = 0
        private set

    /** What [flush] answers; false makes the owner rebuild the decoder. */
    var flushSucceeds = true

    /** The last [keyframesOnly] the owner set, including the value given at install time. */
    var keyframesOnlySet: Boolean? = null
        private set

    override var keyframesOnly: Boolean
        get() = keyframesOnlySet ?: false
        set(value) {
            keyframesOnlySet = value
        }

    override fun flush(): Boolean {
        flushes++
        return flushSucceeds
    }

    /** What [queuedFrames] answers. */
    var queued = 0

    /** What [framesPerSecond] answers. */
    var fps = 0f

    /** What [outputAgeMillis] answers. */
    var outputAge = 0L

    override val queuedFrames: Int get() = queued

    override fun framesPerSecond(nowMillis: Long): Float = fps

    override fun outputAgeMillis(nowMillis: Long): Long = outputAge

    override fun start() {
        failOnStart?.let { throw it }
        started = true
    }

    override fun feed(unit: AccessUnit) {
        fed += unit
    }

    override fun checkStall(nowMillis: Long): Boolean = stalled

    override fun release() {
        releasedOn = Thread.currentThread()
        released = true
    }

    /** Simulates the codec's first rendered picture. */
    fun emitFirstFrame() {
        firstFrameEmitted = true
        listener.onFirstFrame(this)
    }

    private var firstFrameEmitted = false

    override val hasOutput: Boolean get() = firstFrameEmitted
}

/** Builds [RecordingDecoder]s and keeps them for assertions. */
class RecordingDecoderFactory {
    /** Decoders created so far, in order. */
    val created = CopyOnWriteArrayList<RecordingDecoder>()

    /** Codec names that must fail at start with the given exception, consumed in order. */
    val startFailures = ArrayDeque<Pair<String, Throwable>>()

    /** The factory for [TileDecoder]'s owners. */
    val factory: DecoderFactory =
        DecoderFactory { name, config, surface, listener, paced ->
            val failure = startFailures.firstOrNull { it.first == name }?.also(startFailures::remove)?.second
            RecordingDecoder(name, config, surface, listener, failure, paced).also(created::add)
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
        // Synchronous: a test asserts `released` right after the stop that caused it.
        releaser = {
            it.release()
            CompletableFuture.completedFuture(null)
        },
    )
}

/** A pool that records submissions instead of decoding. */
class NoopKeyframePool : KeyframeDecoderPool(maxDecoders = 1, pickCodec = { null }) {
    /** Keyframes submitted, by tile. */
    val submitted = CopyOnWriteArrayList<Pair<Any, AccessUnit>>()

    /** Times the coordinator asked the pool to close its codecs (playback stopped). */
    @Volatile var codecsClosed: Int = 0

    override fun closeCodecs() {
        codecsClosed++
    }

    override fun submit(
        tile: Any,
        sink: StillSink,
        unit: AccessUnit,
    ) {
        submitted += tile to unit
    }
}

/**
 * A pool that "decodes" every submission at once: the sink gets a 1 × 1 still synchronously, the
 * way the real pool's worker calls it after a successful decode. [NoopKeyframePool] is the other
 * half of the pair: it swallows every job, like a pool whose decoder is failing.
 */
class DeliveringKeyframePool : KeyframeDecoderPool(maxDecoders = 1, pickCodec = { null }) {
    /** Stills delivered, by tile. */
    val delivered = CopyOnWriteArrayList<Any>()

    override fun submit(
        tile: Any,
        sink: StillSink,
        unit: AccessUnit,
    ) {
        delivered += tile
        sink.onStill(IntArray(1), 1, 1)
    }
}
