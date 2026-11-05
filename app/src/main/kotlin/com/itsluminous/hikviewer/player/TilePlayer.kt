package com.itsluminous.hikviewer.player

import android.media.MediaCodec
import android.util.Log
import android.view.Surface
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.CodecConfig
import com.itsluminous.hikviewer.core.stream.StreamException
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Plays one camera into one surface according to a [TileMode].
 *
 * Collects the camera's [AccessUnit] flow on the caller's scope and routes pictures either to a
 * private [TileDecoder] (live modes) or, as thumbnails, to the shared [KeyframeDecoderPool] through
 * the tile's [StillSink]. Decoders are created lazily at the first keyframe that carries parameter
 * sets, and recreated at the next keyframe after a parameter-set change, a stall or a surface
 * change. A hardware codec that refuses to start ("insufficient resources", error 1100 on the
 * MiTV) is replaced by a software one and hardware is tried again at a keyframe after a backoff
 * that doubles with every refusal ([HW_RETRY_MILLIS] … [HW_RETRY_MAX_MILLIS]): on a TV whose
 * hardware slots are taken by another app the retry is cheap once a minute, not a rebuilt decoder
 * every five seconds. The backoff resets when the user retries, the surface changes or the tile
 * is given hardware afresh (the slot is often held by the tile that just lost focus).
 *
 * Switching between the two live modes (focus moved: hardware ↔ software) keeps the running
 * decoder until the next keyframe and swaps there, so the picture never goes dark for a GOP. The
 * state only goes back to [TileState.Connecting] when there is really no picture: a stream
 * reconnect or a fresh start, never a mode change.
 *
 * Threading: [lock] guards the fields only. No codec is created or released while it is held —
 * creation happens on the stream thread between two short critical sections (a [generation]
 * counter discards a decoder whose tile changed its mind meanwhile), and teardown goes to the
 * [releaser] ([DecoderReleaser] by default) so the main thread and the other stream threads never
 * wait for `MediaCodec.release()`. The one exception is a rebuild on the stream thread itself,
 * which releases the old decoder synchronously because the hardware slot must be free before the
 * new one starts.
 */
class TilePlayer(
    /** The camera shown. */
    val camera: Camera,
    private val opener: StreamOpener,
    private val pool: KeyframeDecoderPool,
    private val quality: StreamQuality = StreamQuality.SUB,
    private val pickCodec: (mimeType: String, software: Boolean) -> String? = CodecNames::pick,
    private val decoderFactory: (String, CodecConfig, Surface, TileDecoder.Listener) -> TileDecoder = ::TileDecoder,
    /** Where the stream is collected and pictures are queued; a test dispatcher in tests. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    /** Tears detached decoders down; tests pass a synchronous one. */
    private val releaser: (TileDecoder) -> Unit = DecoderReleaser::release,
) : TileDecoder.Listener {
    private val lock = Any()
    private val stateFlow = MutableStateFlow<TileState>(TileState.Connecting)
    private val frameRateFlow = MutableStateFlow(0f)
    private val keyframeRate = FrameRateMeter()
    private val tag = "ch${camera.channelNo}"
    private var surface: Surface? = null
    private var sink: StillSink? = null
    private var decoder: TileDecoder? = null
    private var decoderConfig: CodecConfig? = null
    private var decoderIsSoftware = false
    private var hardwareRetryAt = 0L
    private var hardwareBackoffMillis = HW_RETRY_MILLIS
    private var decoderDisabled = false
    private var swapAtNextKeyframe = false
    private var generation = 0
    private var job: Job? = null
    private var streaming = false
    private var pictureShown = false

    /** For the overlay. */
    val state: StateFlow<TileState> = stateFlow.asStateFlow()

    /**
     * Rendered frames per second, sampled on every [tick] from the live decoder's output callbacks
     * (or the keyframe submissions of a keyframe-only tile) and rounded to whole frames, which is
     * what the counter shows: a steady 12 fps tile then publishes nothing instead of a new value
     * every half second (the meter's 2 s window flips between 11.5 and 12.5 at every tick).
     */
    val frameRate: StateFlow<Float> = frameRateFlow.asStateFlow()

    /** Decoders this player built so far; the TV logcat shows churn through it. */
    val decodersCreated: Int get() = synchronized(lock) { created }
    private var created = 0

    /**
     * Current mode. Live → keyframe releases the private decoder at once (it must free the codec);
     * keyframe → live builds one at the next keyframe; live → live swaps at the next keyframe.
     */
    var mode: TileMode = TileMode.KEYFRAME
        set(value) {
            var stale: TileDecoder? = null
            synchronized(lock) {
                val previous = field
                if (previous == value) return
                field = value
                generation++
                decoderDisabled = false
                if (value == TileMode.HW_LIVE) resetHardwareBackoffLocked()
                when {
                    !value.isLive -> {
                        stale = detachDecoderLocked()
                        if (streaming) stateFlow.value = TileState.Keyframe
                    }
                    previous.isLive && decoder != null -> swapAtNextKeyframe = true
                }
                if (value.isLive) pool.cancel(this)
            }
            stale?.let(releaser)
        }

    /** Decoder input backlog, for [LiveBudget]. */
    val queuedFrames: Int get() = synchronized(lock) { decoder?.queuedFrames ?: 0 }

    /** Starts collecting the stream on [scope]. */
    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (job != null) return
            stateFlow.value = TileState.Connecting
            pictureShown = false
            // Off the main thread: codec creation takes ~100 ms and every picture is copied into
            // an input buffer; the UI must not pay for either.
            job =
                scope.launch(dispatcher) {
                    opener
                        .open(camera, quality, ::onStatus)
                        .catch { e -> onStreamFailed(e) }
                        .collect(::onAccessUnit)
                }
        }
    }

    /** Stops the stream and releases the decoder. */
    fun stop() {
        val stale: TileDecoder?
        synchronized(lock) {
            job?.cancel()
            job = null
            streaming = false
            generation++
            stale = detachDecoderLocked()
            pool.cancel(this)
            keyframeRate.reset()
            frameRateFlow.value = 0f
        }
        stale?.let(releaser)
    }

    /** The user pressed Retry on a failed tile: forget the failure and open the stream again. */
    fun retry(scope: CoroutineScope) {
        stop()
        synchronized(lock) {
            decoderDisabled = false
            resetHardwareBackoffLocked()
        }
        start(scope)
    }

    /** The tile's surface appeared or vanished; decoding restarts at the next keyframe. */
    fun attachSurface(surface: Surface?) {
        val stale: TileDecoder?
        synchronized(lock) {
            if (this.surface == surface) return
            this.surface = surface
            generation++
            stale = detachDecoderLocked()
            decoderDisabled = false
            resetHardwareBackoffLocked()
        }
        stale?.let(releaser)
    }

    /** Where this tile's keyframe thumbnails are drawn while it is not live; null while it has no view. */
    fun attachStillSink(sink: StillSink?) {
        synchronized(lock) { this.sink = sink }
    }

    /** Periodic watchdog; recreates a wedged decoder and samples the frame rate. Returns true when it had to. */
    fun tick(nowMillis: Long): Boolean {
        var stale: TileDecoder? = null
        val stalled =
            synchronized(lock) {
                val stalled = decoder?.checkStall(nowMillis) == true
                if (stalled) {
                    Log.w(TAG, "$tag: decoder stalled, recreating (decoder #${created + 1} next)")
                    stale = detachDecoderLocked()
                }
                val fps =
                    when {
                        !streaming -> 0f
                        mode.isLive -> decoder?.framesPerSecond(nowMillis) ?: 0f
                        else -> keyframeRate.fps(nowMillis)
                    }
                frameRateFlow.value = Math.round(fps).toFloat()
                stalled
            }
        stale?.let(releaser)
        return stalled
    }

    override fun onFirstFrame(decoder: TileDecoder) {
        synchronized(lock) {
            if (this.decoder !== decoder) return
            pictureShown = true
            stateFlow.value = TileState.Live
        }
    }

    override fun onError(
        decoder: TileDecoder,
        exception: MediaCodec.CodecException,
    ) {
        val stale: TileDecoder?
        synchronized(lock) {
            if (this.decoder !== decoder) return
            Log.w(TAG, "$tag: codec error ${exception.errorCode} transient=${exception.isTransient}")
            stale = detachDecoderLocked()
            if (DecoderPolicy.shouldFallBackToSoftware(exception.isTransient, exception.errorCode)) {
                deferHardwareLocked()
            } else {
                failDecoderLocked("Decoder error")
            }
        }
        stale?.let(releaser)
    }

    private fun onStatus(status: StreamStatus) {
        var stale: TileDecoder? = null
        synchronized(lock) {
            when (status) {
                is StreamStatus.Connecting -> if (!streaming) stateFlow.value = TileState.Connecting
                is StreamStatus.Streaming -> streaming = true
                is StreamStatus.Reconnecting -> {
                    streaming = false
                    pictureShown = false
                    stale = detachDecoderLocked()
                    decoderDisabled = false
                    stateFlow.value = TileState.Connecting
                }
                // A playback window ran out: keep the last picture on the surface; the owner
                // decides whether to open the next window.
                is StreamStatus.Ended -> Unit
            }
        }
        stale?.let(releaser)
    }

    private fun onStreamFailed(e: Throwable) {
        val state =
            when (e) {
                is StreamException.WrongVerificationCode ->
                    TileState.Error("Cannot decrypt", wrongVerificationCode = true)
                is StreamException.ChannelOffline -> TileState.Error("No video")
                is StreamException.ChannelNotStreamable -> TileState.Error("No cloud stream")
                is StreamException -> TileState.Error("Relay refused")
                is IOException -> TileState.Error("Offline")
                else -> TileState.Error("Error")
            }
        Log.w(TAG, "$tag: stream failed: ${e.message}")
        val stale: TileDecoder?
        synchronized(lock) {
            stale = detachDecoderLocked()
            stateFlow.value = state
        }
        stale?.let(releaser)
    }

    /** What to do with one picture; decided under the lock, done outside it. */
    private sealed class Plan {
        object Skip : Plan()

        class Feed(
            val decoder: TileDecoder,
        ) : Plan()

        class Still(
            val sink: StillSink,
        ) : Plan()

        class Rebuild(
            val config: CodecConfig,
            val target: Surface,
            val software: Boolean,
            val stale: TileDecoder?,
            val generation: Int,
        ) : Plan()
    }

    private fun onAccessUnit(unit: AccessUnit) {
        when (val plan = synchronized(lock) { plan(unit) }) {
            Plan.Skip -> Unit
            is Plan.Feed -> plan.decoder.feed(unit)
            is Plan.Still -> pool.submit(this, plan.sink, unit)
            is Plan.Rebuild -> rebuild(plan, unit)
        }
    }

    private fun plan(unit: AccessUnit): Plan = if (mode.isLive) planLive(unit) else planStill(unit)

    private fun planStill(unit: AccessUnit): Plan {
        val target = sink
        if (!unit.isKeyframe || target == null) return Plan.Skip
        keyframeRate.onFrame(clock())
        pictureShown = true
        stateFlow.value = TileState.Keyframe
        return Plan.Still(target)
    }

    private fun planLive(unit: AccessUnit): Plan {
        val target = surface
        if (target == null || decoderDisabled) return Plan.Skip
        val config = unit.config
        if (!needsNewDecoder(config)) return decoder?.let(Plan::Feed) ?: Plan.Skip
        if (!unit.isKeyframe || config == null) return Plan.Skip
        val software = mode != TileMode.HW_LIVE || clock() < hardwareRetryAt
        return Plan.Rebuild(config, target, software, detachDecoderLocked(), generation)
    }

    /** True when the next keyframe must build a (different) decoder. */
    private fun needsNewDecoder(config: CodecConfig?): Boolean {
        val wantsHardwareBack = mode == TileMode.HW_LIVE && decoderIsSoftware && clock() >= hardwareRetryAt
        return decoder == null || swapAtNextKeyframe || wantsHardwareBack || (config != null && config != decoderConfig)
    }

    /**
     * Builds the decoder outside the lock and installs it only if the tile still wants exactly
     * this one: a stop, a mode or a surface change meanwhile bumped [generation], and the fresh
     * decoder is released instead of fed.
     */
    private fun rebuild(
        plan: Plan.Rebuild,
        unit: AccessUnit,
    ) {
        plan.stale?.release() // synchronously: the hardware slot must be free before the next start
        val (d, software) = createDecoder(plan.config, plan.target, plan.software) ?: return
        val installed =
            synchronized(lock) {
                val wanted = generation == plan.generation && decoder == null && mode.isLive
                if (wanted) {
                    decoder = d
                    decoderConfig = plan.config
                    decoderIsSoftware = software
                    created++
                }
                wanted
            }
        if (installed) d.feed(unit) else releaser(d)
    }

    /** A started decoder and whether it is a software one, or null when the tile is now in error. */
    private fun createDecoder(
        config: CodecConfig,
        target: Surface,
        software: Boolean,
    ): Pair<TileDecoder, Boolean>? {
        val name = pickCodec(config.codec.mimeType, software)
        if (name == null) {
            failDecoder("No ${config.codec} decoder")
            return null
        }
        val d = decoderFactory(name, config, target, this)
        return try {
            d.start()
            Log.i(TAG, "$tag: ${config.codec} via $name (${if (software) "software" else "hardware"})")
            d to software
        } catch (e: MediaCodec.CodecException) {
            d.release()
            if (!software && DecoderPolicy.shouldFallBackToSoftware(e.isTransient, e.errorCode)) {
                val wait = synchronized(lock) { deferHardwareLocked() }
                val seconds = wait / MILLIS_PER_SECOND
                Log.w(TAG, "$tag: hardware decoder unavailable (${e.errorCode}), software for $seconds s")
                createDecoder(config, target, software = true)
            } else {
                failDecoder("Decoder error")
                null
            }
        } catch (e: IOException) {
            d.release()
            Log.w(TAG, "$tag: cannot create $name", e)
            failDecoder("Decoder error")
            null
        } catch (e: IllegalArgumentException) {
            // `configure` throws this when the surface is unusable: gone, or still held by another
            // codec. It must not take the process down; the tile shows Retry instead.
            d.release()
            Log.w(TAG, "$tag: cannot configure $name", e)
            failDecoder("Decoder error")
            null
        } catch (e: IllegalStateException) {
            d.release()
            Log.w(TAG, "$tag: codec in a bad state at start", e)
            failDecoder("Decoder error")
            null
        }
    }

    /** Hardware refused: software until the backoff passes, and the next backoff is twice as long. */
    private fun deferHardwareLocked(): Long {
        val wait = hardwareBackoffMillis
        hardwareRetryAt = clock() + wait
        hardwareBackoffMillis = (wait * 2).coerceAtMost(HW_RETRY_MAX_MILLIS)
        return wait
    }

    private fun resetHardwareBackoffLocked() {
        hardwareRetryAt = 0L
        hardwareBackoffMillis = HW_RETRY_MILLIS
    }

    private fun failDecoder(message: String) = synchronized(lock) { failDecoderLocked(message) }

    /** A decoder failure a retry cannot fix: stop trying until the mode, surface or stream changes. */
    private fun failDecoderLocked(message: String) {
        decoderDisabled = true
        stateFlow.value = TileState.Error(message)
    }

    /** Forgets the decoder; the caller releases what comes back, outside the lock. */
    private fun detachDecoderLocked(): TileDecoder? {
        val stale = decoder
        decoder = null
        decoderConfig = null
        swapAtNextKeyframe = false
        return stale
    }

    /** Tunables. */
    companion object {
        /** After a hardware decoder refused to start, software is used for this long before trying again. */
        const val HW_RETRY_MILLIS: Long = 5_000

        /** The backoff doubles with every refusal up to this. */
        const val HW_RETRY_MAX_MILLIS: Long = 5 * 60_000

        private const val TAG = "TilePlayer"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
