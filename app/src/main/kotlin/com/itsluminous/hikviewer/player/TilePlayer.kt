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
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Plays one camera into one surface according to a [TileMode].
 *
 * Collects the camera's [AccessUnit] flow on the caller's scope and routes pictures either to a
 * private [TileDecoder] (live modes) or, as thumbnails, to the shared [KeyframeDecoderPool] through
 * the tile's [StillSink]. Decoders are created lazily at the first keyframe that carries parameter
 * sets, and recreated at the next keyframe after a parameter-set change, a stall or a surface
 * change. A hardware codec that fails, at start ("insufficient resources", error 1100 on the
 * MiTV, or any other code, exception or diagnostic a vendor decoder produces) or later through
 * its callback, is replaced by a software one and hardware is tried again at a keyframe after a
 * backoff that doubles with every failure ([HW_RETRY_MILLIS] … [HW_RETRY_MAX_MILLIS]): on a TV
 * whose hardware slots are taken by another app the retry is cheap once a minute, not a rebuilt
 * decoder every five seconds, and a decoder that fails for a reason of its own does not flap. The
 * backoff resets when the user retries, the surface changes or the tile is given hardware afresh
 * (the slot is often held by the tile that was the hero a moment ago). Only a software decoder
 * that fails is a tile error; what the codec said is kept in [lastDecoderError] and shown in the
 * state ([TileState.Error.detail]) so a user can read it off the screen.
 *
 * Switching between the two live modes (the hero moved: hardware ↔ software) keeps the running
 * decoder until the next keyframe and swaps there, so the picture never goes dark for a GOP. The
 * state only goes back to [TileState.Connecting] when there is really no picture: a stream
 * reconnect or a fresh start, never a mode change.
 *
 * Threading: [lock] guards the fields only. No codec is created or released while it is held:
 * creation happens on the stream thread between two short critical sections (a [generation]
 * counter discards a decoder whose tile changed its mind meanwhile), and teardown goes to the
 * [releaser] ([DecoderReleaser] by default) so the main thread and the other stream threads never
 * wait for `MediaCodec.release()`. Two exceptions release synchronously: a rebuild on the stream
 * thread (the hardware slot must be free before the new decoder starts) and a surface change
 * ([attachSurface]: the surface is about to be freed, see there).
 */
class TilePlayer(
    /** The camera shown. */
    val camera: Camera,
    private val opener: StreamOpener,
    private val pool: KeyframeDecoderPool,
    private val quality: StreamQuality = StreamQuality.SUB,
    private val pickCodec: (mimeType: String, software: Boolean) -> String? = CodecNames::pick,
    private val decoderFactory: DecoderFactory = DecoderFactory.DEFAULT,
    /** Where the stream is collected and pictures are queued; a test dispatcher in tests. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    /** Tears detached decoders down; tests pass a synchronous one. */
    private val releaser: (TileDecoder) -> Future<*> = DecoderReleaser::release,
) : TileDecoder.Listener {
    private val lock = Any()
    private val stateFlow = MutableStateFlow<TileState>(TileState.Connecting)
    private val frameRateFlow = MutableStateFlow(0f)
    private val flowingFlow = MutableStateFlow(false)
    private val livenessFlow = MutableStateFlow(TileLiveness.NOT_LIVE)
    private var lastStillAtMillis = 0L
    private val keyframeRate = FrameRateMeter()
    private val arrivalGaps = IntervalStats()
    private val stillGaps = IntervalStats()
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
    private val releasing = ArrayList<Future<*>>()
    private var job: Job? = null
    private var streaming = false
    private var pictureShown = false
    private var unitsReceived = 0
    private var lastUnitAtMillis = 0L
    private var configureRefusals = 0

    /** For the overlay. */
    val state: StateFlow<TileState> = stateFlow.asStateFlow()

    /**
     * The last decoder failure as [DecoderPolicy.describeFailure] worded it (codec name, error
     * code, flags and `getDiagnosticInfo()`), whether it was answered with a software decoder or
     * with a tile error; null until one happens. The periodic log repeats it and the error state
     * carries it, so a report from a TV we cannot touch still names the codec and the code.
     */
    val lastDecoderError: String? get() = synchronized(lock) { lastFailure }
    private var lastFailure: String? = null

    /**
     * Rendered frames per second, sampled on every [tick] from the live decoder's output callbacks
     * (or the stills the pool delivered to a keyframe-only tile) and rounded to whole frames, which is
     * what the counter shows: a steady 12 fps tile then publishes nothing instead of a new value
     * every half second (the meter's 2 s window flips between 11.5 and 12.5 at every tick).
     */
    val frameRate: StateFlow<Float> = frameRateFlow.asStateFlow()

    /**
     * Whether pictures reach the screen, and at which rate: a decoded frame (live) or a keyframe
     * still (keyframe mode) within the last [TileLiveness.FLOWING_MILLIS]. Sampled on every [tick],
     * so the overlays change at most twice a second and never per frame. The dot and the top
     * bar's count both read this, so they can never disagree.
     */
    val liveness: StateFlow<TileLiveness> = livenessFlow.asStateFlow()

    /** [liveness] as the dot sees it: true for both picture-showing states. */
    val framesFlowing: StateFlow<Boolean> = flowingFlow.asStateFlow()

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
            stale?.let(::releaseLater)
        }

    /**
     * Whether the live decoder evens the pictures out (a [FramePacer] holding each one half a
     * second so the relay's bunched delivery does not reach the screen, and the shared 15 Hz
     * [RenderPacer] cadence) or shows each one the moment it is decoded. A grid tile is paced
     * whatever decodes it; the hero and the single-camera screens (fullscreen, phone camera,
     * recorded playback) are not, because there the half second and the capped rate would be
     * noticed, and a hardware decoder refused for a while (software until the backoff passes)
     * must not change that. Changing it on a live decoder swaps at the next keyframe, like a mode
     * change between the two live modes.
     */
    var paced: Boolean = true
        set(value) {
            synchronized(lock) {
                if (field == value) return
                field = value
                generation++
                if (decoder != null) swapAtNextKeyframe = true
            }
        }

    /**
     * Feed the decoder keyframes only. Reverse recorded playback hops backwards through the day,
     * so the P-frames of a hop predict from pictures that were never decoded; each hop shows its
     * keyframe and nothing else ([KeyframeGate]). Non-keyframes are dropped here, before the copy
     * into an input buffer, and the decoder's own gate drops them again if one slips through.
     */
    var keyframesOnly: Boolean = false
        set(value) {
            synchronized(lock) {
                field = value
                decoder?.keyframesOnly = value
            }
        }

    /**
     * Show every Nth decoded picture ([PresentationRate]); applied to the running decoder at once
     * and to every decoder built later. Decoding work is unchanged.
     */
    var presentEvery: Int = PresentationRate.FULL_RATE
        set(value) {
            synchronized(lock) {
                field = value
                decoder?.presentEvery = value
            }
        }

    /** Decoder input backlog, for [LiveBudget]. */
    val queuedFrames: Int get() = synchronized(lock) { decoder?.queuedFrames ?: 0 }

    /** Starts collecting the stream on [scope]. */
    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (job != null) return
            stateFlow.value = TileState.Connecting
            pictureShown = false
            val epoch = ++streamEpoch
            // Off the main thread: codec creation takes ~100 ms and every picture is copied into
            // an input buffer; the UI must not pay for either.
            job =
                scope.launch(dispatcher) {
                    opener
                        .open(camera, quality, ::onStatus)
                        .catch { e -> onStreamFailed(e) }
                        .collect { unit -> onAccessUnit(unit, epoch) }
                }
        }
    }

    /**
     * Which stream the pictures belong to. Cancelling a stream's job does not stop a picture that
     * is already on its way through [onAccessUnit] on the stream thread; without this check a
     * P-frame of the window just closed could land in the decoder after a [reopen], predicted
     * from a picture of a different time, and that is one way to a green or grey picture.
     */
    private var streamEpoch = 0

    /**
     * Closes the stream and opens it again on [scope], keeping the decoder: it is flushed and
     * waits for the next keyframe ([TileDecoder.flush]), so the last picture stays on the surface
     * (under the connecting spinner) until the new stream's first keyframe replaces it. This is
     * what a seek or a speed change in recorded playback does; [stop] + [start] would hand the
     * hardware slot back, show a blank surface (black or green, depending on the SoC) for the
     * second it takes to configure a new codec and decode its first picture, and race the codec
     * being released for the surface. A decoder that refuses the flush is released and rebuilt at
     * the next keyframe instead.
     */
    fun reopen(scope: CoroutineScope) {
        var stale: TileDecoder? = null
        synchronized(lock) {
            job?.cancel()
            job = null
            streaming = false
            generation++
            streamEpoch++
            pool.cancel(this)
            arrivalGaps.clear()
            lastUnitAtMillis = 0L
            val d = decoder
            if (d != null && !d.flush()) {
                Log.w(TAG, "$tag: decoder refused to flush, rebuilding at the next keyframe")
                stale = detachDecoderLocked()
            }
            // Connecting over the held picture: the spinner says a new window is on its way, and
            // the kept decoder reports its next picture as a first frame, which clears it.
            stateFlow.value = TileState.Connecting
            pictureShown = false
        }
        stale?.let(::releaseLater)
        start(scope)
    }

    /** Stops the stream and releases the decoder. */
    fun stop() {
        val stale: TileDecoder?
        synchronized(lock) {
            job?.cancel()
            job = null
            streaming = false
            generation++
            streamEpoch++
            stale = detachDecoderLocked()
            pool.cancel(this)
            keyframeRate.reset()
            arrivalGaps.clear()
            stillGaps.clear()
            lastStillAtMillis = 0L
            frameRateFlow.value = 0f
            livenessFlow.value = TileLiveness.NOT_LIVE
            flowingFlow.value = false
        }
        stale?.let(::releaseLater)
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

    /**
     * The tile's surface appeared, vanished or was replaced; decoding restarts at the next
     * keyframe. This is the one teardown that stays on the caller's thread: it runs from
     * `surfaceDestroyed`, after which the framework frees the surface, and a codec still drawing
     * into a freed surface tears its own buffers down under the thread feeding it (SIGSEGV in
     * `TileDecoder.drain` on the emulator's Codec2 decoders). So the detached decoder is released
     * here and now, and any release of this player's earlier decoders still in flight is waited
     * for; both are short and only happen when a screen goes away.
     */
    fun attachSurface(surface: Surface?) {
        val stale: TileDecoder?
        val inFlight: List<Future<*>>
        synchronized(lock) {
            if (this.surface == surface) return
            this.surface = surface
            generation++
            stale = detachDecoderLocked()
            decoderDisabled = false
            resetHardwareBackoffLocked()
            inFlight = releasing.toList()
            releasing.clear()
        }
        stale?.release()
        inFlight.forEach { runCatching { it.get(RELEASE_WAIT_MILLIS, TimeUnit.MILLISECONDS) } }
    }

    /** Hands [decoder] to the releaser and remembers the future until the surface changes. */
    private fun releaseLater(decoder: TileDecoder) {
        val future = releaser(decoder)
        synchronized(lock) {
            releasing.removeAll { it.isDone }
            releasing += future
        }
    }

    /**
     * Where this tile's keyframe thumbnails are drawn while it is not live; null while it has no
     * view. The sink is wrapped so the player sees every picture the pool actually delivers: the
     * green dot and the "1 fps" counter of a keyframe tile mean rendered stills, not keyframes
     * handed to the pool. A pool whose decoder fails or starves ([KeyframeDecoderPool] recreates it
     * after a few lost jobs) then shows a red dot over the stale still instead of a green one.
     */
    fun attachStillSink(sink: StillSink?) {
        synchronized(lock) { this.sink = sink?.let(::StampingSink) }
    }

    /** Delegates to the tile's sink and stamps the time of every delivered still; called on the pool's worker. */
    private inner class StampingSink(
        private val target: StillSink,
    ) : StillSink by target {
        override fun onStill(
            pixels: IntArray,
            width: Int,
            height: Int,
        ) {
            val n =
                synchronized(lock) {
                    val at = clock()
                    lastStillAtMillis = at
                    keyframeRate.onFrame(at)
                    stillGaps.onEvent(at)
                    ++stillsDelivered
                }
            if (n <= FIRST_STILLS_LOGGED) Log.i(TAG, "$tag: still #$n ${width}x$height handed to $target")
            target.onStill(pixels, width, height)
        }
    }

    private var stillsDelivered = 0

    /**
     * Periodic watchdog; recreates a wedged decoder and samples the frame rate. Returns the number
     * of pictures that were waiting at the wedged decoder's input, or null when it was fine: the
     * budget treats a stall as lag only when the queue says the device could not keep up. The
     * MiTV's software decoder wedges with an empty queue (a decoder bug, not load), and shedding a
     * tile for that only turns a hiccup into a slideshow.
     */
    fun tick(nowMillis: Long): Int? {
        var stale: TileDecoder? = null
        val queuedAtStall =
            synchronized(lock) {
                val d = decoder
                val stalled = d != null && d.checkStall(nowMillis)
                if (stalled) {
                    Log.w(
                        TAG,
                        "$tag: decoder stalled, recreating (decoder #${created + 1} next): " +
                            "no output for ${d.outputAgeMillis(nowMillis)} ms, last input " +
                            "${d.inputAgeMillis(nowMillis)} ms ago, queued=${d.queuedFrames} " +
                            "in=${d.framesIn} out=${d.framesOut}",
                    )
                    Log.w(TAG, "$tag: ${d.recentHistory()}")
                    stale = detachDecoderLocked()
                }
                val fps =
                    when {
                        !streaming -> 0f
                        mode.isLive -> decoder?.framesPerSecond(nowMillis) ?: 0f
                        else -> keyframeRate.fps(nowMillis)
                    }
                frameRateFlow.value = Math.round(fps).toFloat()
                val lastPictureAge =
                    when {
                        !streaming -> Long.MAX_VALUE
                        mode.isLive -> decoder?.takeIf { it.hasOutput }?.outputAgeMillis(nowMillis) ?: Long.MAX_VALUE
                        else -> if (lastStillAtMillis == 0L) Long.MAX_VALUE else nowMillis - lastStillAtMillis
                    }
                val liveness = TileLiveness.of(mode, lastPictureAge)
                livenessFlow.value = liveness
                flowingFlow.value = liveness.isFlowing
                if (stalled) d.queuedFrames else null
            }
        stale?.let(::releaseLater)
        return queuedAtStall
    }

    /**
     * One line of state for the periodic INFO log: mode, overlay state, pictures received from the
     * stream, decoder input/output counts, queue depth, rendered fps, and the inter-picture gaps of
     * the window just ended, both as they arrived from the cloud (`arrive=`) and as they reached
     * the screen (`shown=`, decoder output or delivered stills). Cheap enough to log every few
     * seconds per tile; this is what a TV logcat is read with.
     */
    fun diagnostics(nowMillis: Long): String = synchronized(lock) {
        val d = decoder
        val state =
            when (val v = stateFlow.value) {
                TileState.Connecting -> "Connecting"
                TileState.Live -> "Live"
                TileState.Keyframe -> "Keyframe"
                is TileState.Error -> "Error(${v.message})"
            }
        val unitAge = if (lastUnitAtMillis == 0L) -1 else nowMillis - lastUnitAtMillis
        val dec =
            if (d == null) {
                "decoder=none"
            } else {
                "in=${d.framesIn} out=${d.framesOut} queued=${d.queuedFrames} " +
                    "outAge=${d.outputAgeMillis(nowMillis)} fps=${Math.round(d.framesPerSecond(nowMillis))} " +
                    d.describeGate() + d.describeInput() + d.describePacer()
            }
        val stillAge = if (lastStillAtMillis == 0L) -1 else nowMillis - lastStillAtMillis
        val stills = if (mode.isLive) "" else " stills=$stillsDelivered stillAge=$stillAge"
        val arrive = arrivalGaps.describe()
        arrivalGaps.reset()
        val shown =
            if (mode.isLive) {
                d?.describeOutputGaps() ?: "n=0"
            } else {
                stillGaps.describe().also { stillGaps.reset() }
            }
        val failure = lastFailure?.let { " lastError[$it]" } ?: ""
        "$tag mode=$mode state=$state units=$unitsReceived unitAge=$unitAge $dec created=$created$stills " +
            "arrive[$arrive] shown[$shown]$failure"
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
            val software = decoderIsSoftware
            val detail = DecoderPolicy.describeFailure(decoder.codecName, exception)
            lastFailure = detail
            stale = detachDecoderLocked()
            // A software decoder that fails while running (not at start) decoded pictures before,
            // so the codec exists and works here; the second chance on hardware is for a software
            // decoder that cannot even be started, which afterStartFailure handles.
            when (DecoderPolicy.afterDecoderFailure(software, hardwareTried = true)) {
                DecoderPolicy.FailureAction.RETRY_WITH_SOFTWARE -> {
                    // The next keyframe rebuilds with software; hardware waits out the backoff.
                    val wait = deferHardwareLocked()
                    Log.w(TAG, "$tag: hardware decoder failed, software for ${wait / MILLIS_PER_SECOND} s: $detail")
                }
                DecoderPolicy.FailureAction.RETRY_WITH_HARDWARE,
                DecoderPolicy.FailureAction.TILE_ERROR,
                -> {
                    Log.w(TAG, "$tag: software decoder failed: $detail")
                    failDecoderLocked(DECODER_ERROR, detail)
                }
            }
        }
        stale?.let(::releaseLater)
    }

    private fun onStatus(status: StreamStatus) {
        var stale: TileDecoder? = null
        synchronized(lock) {
            when (status) {
                is StreamStatus.Connecting -> {
                    if (status.attempt > 1) Log.i(TAG, "$tag: connecting, attempt ${status.attempt}")
                    if (!streaming) stateFlow.value = TileState.Connecting
                }
                is StreamStatus.Streaming -> {
                    Log.i(TAG, "$tag: streaming")
                    streaming = true
                }
                is StreamStatus.Reconnecting -> {
                    // The class name says enough; a cause's message can carry the recorder's serial.
                    Log.w(
                        TAG,
                        "$tag: stream lost (${status.cause.javaClass.simpleName}), " +
                            "reconnect #${status.attempt} in ${status.delayMillis} ms",
                    )
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
        stale?.let(::releaseLater)
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
        stale?.let(::releaseLater)
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
            val paced: Boolean,
            val stale: TileDecoder?,
            val generation: Int,
        ) : Plan()
    }

    private fun onAccessUnit(
        unit: AccessUnit,
        epoch: Int,
    ) {
        val plan =
            synchronized(lock) {
                if (epoch != streamEpoch) return
                unitsReceived++
                lastUnitAtMillis = clock()
                arrivalGaps.onEvent(lastUnitAtMillis)
                plan(unit)
            }
        when (plan) {
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
        pictureShown = true
        stateFlow.value = TileState.Keyframe
        return Plan.Still(target)
    }

    private fun planLive(unit: AccessUnit): Plan {
        val target = surface
        if (target == null || decoderDisabled) return Plan.Skip
        if (keyframesOnly && !unit.isKeyframe) return Plan.Skip
        val config = unit.config
        if (!needsNewDecoder(config)) return decoder?.let(Plan::Feed) ?: Plan.Skip
        if (!unit.isKeyframe || config == null) return Plan.Skip
        val software = mode != TileMode.HW_LIVE || clock() < hardwareRetryAt
        return Plan.Rebuild(config, target, software, paced, detachDecoderLocked(), generation)
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
        val request =
            DecoderRequest(plan.config, plan.target, plan.software, plan.paced, TileDecoder.inputSizeFor(unit.size))
        val (d, software) = createDecoder(request) ?: return
        val installed =
            synchronized(lock) {
                val wanted = generation == plan.generation && decoder == null && mode.isLive
                if (wanted) {
                    d.keyframesOnly = keyframesOnly
                    d.presentEvery = presentEvery
                    decoder = d
                    decoderConfig = plan.config
                    decoderIsSoftware = software
                    created++
                }
                wanted
            }
        if (installed) d.feed(unit) else releaseLater(d)
    }

    /** What a decoder is built for: the parameter sets, the surface, the kind, and the input buffer size. */
    private class DecoderRequest(
        val config: CodecConfig,
        val target: Surface,
        val software: Boolean,
        val paced: Boolean,
        val maxInputSize: Int,
    ) {
        fun withSoftware(software: Boolean) = DecoderRequest(config, target, software, paced, maxInputSize)
    }

    /** A started decoder and whether it is a software one, or null when the tile is now in error. */
    private fun createDecoder(request: DecoderRequest): Pair<TileDecoder, Boolean>? {
        val config = request.config
        val software = request.software
        val paced = request.paced
        val name = pickCodec(config.codec.mimeType, software)
        if (name == null) {
            failDecoder(
                "No ${config.codec} decoder",
                "no ${if (software) "software" else "hardware"} ${config.codec.mimeType} decoder",
            )
            return null
        }
        val d =
            decoderFactory.create(
                name,
                config,
                request.target,
                this,
                paced = paced,
                maxInputSize = request.maxInputSize,
            )
        d.renderSlotNanos = if (paced) RenderPacer.DEFAULT_SLOT_NANOS else 0L
        return try {
            d.start()
            synchronized(lock) { configureRefusals = 0 }
            val kind = if (software) "software" else "hardware"
            Log.i(TAG, "$tag: ${config.codec} via $name ($kind, ${if (paced) "paced" else "unpaced"})")
            d to software
        } catch (e: MediaCodec.CodecException) {
            d.release()
            afterStartFailure(name, e, request)
        } catch (e: IOException) {
            d.release()
            afterStartFailure(name, e, request)
        } catch (e: IllegalStateException) {
            // The codec answered configure() or start() from a state it should not be in.
            d.release()
            afterStartFailure(name, e, request)
        } catch (e: IllegalArgumentException) {
            // `configure` throws this when the surface is unusable: gone, or still held by another
            // codec. On the MiTV the codec released a moment ago can still hold the surface's
            // BufferQueue for a few hundred milliseconds (nativeWindowConnect -22), so the first
            // few refusals are retried at the next keyframe; only a surface that stays unusable
            // is a tile error with Retry. It must never take the process down.
            d.release()
            val attempt = synchronized(lock) { ++configureRefusals }
            val detail = DecoderPolicy.describeFailure(name, e)
            if (attempt <= CONFIGURE_RETRIES) {
                Log.w(TAG, "$tag: cannot configure (attempt $attempt), retrying at a keyframe: $detail")
            } else {
                Log.w(TAG, "$tag: cannot configure, giving up: $detail")
                synchronized(lock) { lastFailure = detail }
                failDecoder(DECODER_ERROR, detail)
            }
            null
        }
    }

    /**
     * The decoder [name] did not start with [failed] ([DecoderPolicy.afterDecoderFailure]): a
     * hardware one is replaced by a software one right away, with hardware deferred for the
     * backoff whatever the code was, so a decoder that fails at once for a reason of its own
     * does not flap between the two at every keyframe; a software one is the tile's error.
     */
    private fun afterStartFailure(
        name: String,
        failed: Throwable,
        request: DecoderRequest,
    ): Pair<TileDecoder, Boolean>? {
        val detail = DecoderPolicy.describeFailure(name, failed)
        val hardwareTried =
            synchronized(lock) {
                lastFailure = detail
                hardwareRetryAt > 0L
            }
        return when (DecoderPolicy.afterDecoderFailure(request.software, hardwareTried)) {
            DecoderPolicy.FailureAction.RETRY_WITH_SOFTWARE -> {
                val wait = synchronized(lock) { deferHardwareLocked() }
                Log.w(TAG, "$tag: hardware decoder failed, software for ${wait / MILLIS_PER_SECOND} s: $detail")
                createDecoder(request.withSoftware(true))
            }
            DecoderPolicy.FailureAction.RETRY_WITH_HARDWARE -> {
                Log.w(TAG, "$tag: software decoder failed, trying hardware once: $detail")
                createDecoder(request.withSoftware(false))
            }
            DecoderPolicy.FailureAction.TILE_ERROR -> {
                Log.w(TAG, "$tag: software decoder failed: $detail")
                failDecoder(DECODER_ERROR, detail)
                null
            }
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

    private fun failDecoder(
        message: String,
        detail: String?,
    ) = synchronized(lock) { failDecoderLocked(message, detail) }

    /** A decoder failure a retry cannot fix: stop trying until the mode, surface or stream changes. */
    private fun failDecoderLocked(
        message: String,
        detail: String?,
    ) {
        decoderDisabled = true
        stateFlow.value = TileState.Error(message, detail = detail)
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

        /** Longest a surface change waits for an in-flight release before giving up on it. */
        private const val RELEASE_WAIT_MILLIS = 2_000L

        /** `configure` refusals (surface still held by the previous codec) retried before the tile gives up. */
        const val CONFIGURE_RETRIES: Int = 3

        /** The overlay text of a tile whose software decoder failed (the detail carries the codec's words). */
        const val DECODER_ERROR: String = "Decoder error"

        private const val TAG = "TilePlayer"
        private const val FIRST_STILLS_LOGGED = 3
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
