package com.itsluminous.hikviewer.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.CodecConfig
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * One `MediaCodec` rendering a live stream into a tile's [Surface].
 *
 * Uses the asynchronous callback API: input buffers are filled as the codec frees them, so the
 * stream thread never blocks on `dequeueInputBuffer` and the depth of [pending] is an honest
 * measure of decode lag for [LiveBudget]. Pictures are rendered as soon as they are decoded
 * (`releaseOutputBuffer(render = true)`), so when a picture is shown is decided by when it is
 * fed. An unpaced decoder (the hero, the single-camera screens) feeds at once for the lowest
 * latency: the picture is copied into a free input buffer on the stream thread that brought it,
 * one of the background-priority `stream-N` threads. A [paced] decoder (the grid tiles) holds
 * each picture in a [FramePacer] until its presentation time, so the screen shows the pictures
 * evenly rather than with the relay's jitter; a picture that is already due is fed by the stream
 * thread too, but most are fed by the wake-up booked for their due time, which runs on the
 * `codec-callbacks` looper every decoder in the process shares, together with the codec's own
 * callbacks. That looper stays at default priority on purpose: it is what the pacing's precision
 * rests on, and its work is small (a 570 KB/s copy for eight tiles, callbacks of a few
 * microseconds), far below what a decoder thread costs; a slow `releaseOutputBuffer` on it would
 * delay every tile's next picture, which the diagnostics' `shown` gap would show. A thread hop
 * per frame and a looper per tile were measured on the 1 GB TV with twelve tiles and bought
 * nothing. A [KeyframeGate] lets nothing but an IRAP through until one has been decoded: at the
 * start, so a mid-GOP start never shows grey blocks, and again after every [flush], which is how a
 * seek or a speed change in recorded playback reuses this decoder instead of building a new one
 * (the surface keeps its last picture instead of going blank, and the hardware slot is never
 * handed back and forth). The parameter sets are queued again after a flush, as `CODEC_CONFIG`
 * buffers, so a decoder that forgot them with its buffers gets them before the keyframe. A
 * watchdog ([checkStall], rules in [StallDetector]) reports a codec that accepts input but
 * produces nothing; the owner then recreates it.
 */
open class TileDecoder(
    /** `MediaCodec` name from [CodecNames]. */
    val codecName: String,
    private val config: CodecConfig,
    private val surface: Surface,
    private val listener: Listener,
    /** Width/height hint; the decoder reads the real size from the SPS. */
    private val sizeHint: Pair<Int, Int> = DEFAULT_SIZE_HINT,
    /** `KEY_MAX_INPUT_SIZE`: how big an access unit the input buffers must hold; see [inputSizeFor]. */
    private val maxInputSize: Int = DEFAULT_MAX_INPUT_SIZE,
    /** The jitter buffer, or null to feed every picture the moment it arrives. */
    private val pacer: FramePacer? = null,
    /**
     * Whether the named codec declares `FEATURE_LowLatency`, which decides whether the format asks
     * for low latency ([inputFormat]). The platform lookup by default; tests pass a constant.
     */
    private val supportsLowLatency: (String) -> Boolean = CodecNames::supportsLowLatency,
) {
    /** Codec events, delivered on the decoder's own thread. */
    interface Listener {
        /** The first picture reached the surface. */
        fun onFirstFrame(decoder: TileDecoder)

        /** The codec failed; [exception] is the platform error. */
        fun onError(
            decoder: TileDecoder,
            exception: MediaCodec.CodecException,
        )
    }

    private val handler = Handler(callbackLooper())
    private val lock = Any()
    private val pending = ArrayDeque<AccessUnit>()
    private val freeInputs = ArrayDeque<Int>()
    private var codec: MediaCodec? = null
    private var released = false
    private var started = false
    private var firstOutputSeen = false
    private val stall = StallDetector(0L)
    private val frameRate = FrameRateMeter()

    /**
     * Frames that are due and not yet handed to the codec; the lag signal. A paced decoder's
     * pictures waiting for their presentation time are not lag, so they are not counted.
     */
    open val queuedFrames: Int get() = synchronized(lock) { pending.size + (pacer?.overdue(now()) ?: 0) }

    /** True when this decoder runs a jitter buffer. */
    val paced: Boolean get() = pacer != null

    /** True once a frame has been rendered. */
    open val hasOutput: Boolean get() = synchronized(lock) { firstOutputSeen }

    /** Pictures rendered per second, measured from the codec's output callbacks. */
    open fun framesPerSecond(nowMillis: Long): Float = synchronized(lock) { frameRate.fps(nowMillis) }

    /** Milliseconds since the last picture came out (since `start()` while none has). */
    open fun outputAgeMillis(nowMillis: Long): Long = synchronized(lock) { stall.outputAgeMillis(nowMillis) }

    /** Milliseconds since the last picture was handed to the codec; `Long.MAX_VALUE` before the first. */
    open fun inputAgeMillis(nowMillis: Long): Long = synchronized(lock) { stall.inputAgeMillis(nowMillis) }

    /** Pictures handed to the codec so far. */
    val framesIn: Int get() = synchronized(lock) { framesQueued }

    /** Pictures the codec produced so far (decoded, whether or not presented). */
    val framesOut: Int get() = synchronized(lock) { framesDecoded }

    /** Pictures handed to the surface so far. */
    val framesShown: Int get() = synchronized(lock) { framesPresented }

    private var framesQueued = 0
    private var framesDecoded = 0
    private var framesPresented = 0
    private val gate = KeyframeGate()
    private var csdToSend = 0
    private var flushes = 0

    /**
     * Admit keyframes only (reverse playback: each hop shows its keyframe, see [KeyframeGate]).
     * Switching it off re-arms the gate, so the next picture fed is again a keyframe.
     */
    open var keyframesOnly: Boolean
        get() = synchronized(lock) { gate.keyframesOnly }
        set(value) = synchronized(lock) { gate.keyframesOnly = value }

    /**
     * Pictures are shown at the next multiple of this many nanoseconds instead of at once, so
     * every decoder in the process that shares the value lands its pictures in the same display
     * frames; 0 renders immediately. See [RenderPacer]: on the MiTV eight live tiles at 12 fps
     * each used to wake SurfaceFlinger for ~60 GPU compositions a second, one per tile per
     * picture, and the UI's own frames (focus border, top bar) queued behind them.
     */
    @Volatile var renderSlotNanos: Long = 0L

    /**
     * Show every Nth decoded picture ([PresentationRate]): 1 shows them all. The others are
     * decoded (a P-frame is the next picture's reference) and returned to the codec unrendered,
     * so they cost no composition. The frame-rate meter and the output-gap diagnostics count
     * presented pictures; the stall watchdog and [framesOut] count decoded ones.
     */
    @Volatile var presentEvery: Int = PresentationRate.FULL_RATE

    private val recent = RecentPictures(RECENT)
    private val outputGaps = IntervalStats()
    private var inputCapacity = -1
    private var oversized = 0

    /** Pictures dropped because they were bigger than the codec's input buffers ([inputCapacity]). */
    val oversizedPictures: Int get() = synchronized(lock) { oversized }

    /**
     * Diagnostic: the codec's input buffer size and the pictures too big for it, then the last
     * pictures fed (pts/size, `K` for a keyframe) and the last output timestamps.
     */
    fun recentHistory(): String = synchronized(lock) { "cap=$inputCapacity oversized=$oversized ${recent.describe()}" }

    /** Diagnostic: the codec's input buffer size and the pictures dropped for not fitting it. */
    fun describeInput(): String = synchronized(lock) { " cap=$inputCapacity oversized=$oversized" }

    /** Diagnostic: the jitter buffer's fill and its re-base counters, or an empty string for an unpaced decoder. */
    fun describePacer(): String = synchronized(lock) {
        val p = pacer ?: return ""
        " paced=${p.size} rebuffers=${p.rebuffers} remaps=${p.remaps} catchUps=${p.catchUps} dropped=${p.dropped}"
    }

    /** Diagnostic: the keyframe gate's state and counters, and how often this decoder was flushed. */
    open fun describeGate(): String = synchronized(lock) {
        "shown=$framesPresented present=1/$presentEvery ${gate.describe()} flushes=$flushes"
    }

    /**
     * Diagnostic: the gaps between the pictures that came out since the last call (mean ± stddev
     * and max, in milliseconds), then starts a new window. An even 12 fps tile reads `gap=83±5`;
     * a tile shown in GOP bursts reads a small mean with a stddev and a max near a second.
     */
    fun describeOutputGaps(): String = synchronized(lock) {
        val text = outputGaps.describe()
        outputGaps.reset()
        text
    }

    /** Configures and starts the codec. Throws `MediaCodec.CodecException`/`IOException` on failure. */
    open fun start() {
        val format =
            inputFormat(config, sizeHint, lowLatency = supportsLowLatency(codecName), maxInputSize = maxInputSize)
        val c = MediaCodec.createByCodecName(codecName)
        // Owned from this point on: a configure() that fails (error 1100 on the MiTV when both
        // hardware instances are busy) must still be followed by release(), or the codec stays
        // registered with the media resource manager and the hardware slot is lost until the
        // process dies. Both slots were found held by stale clients of this app on the real TV.
        synchronized(lock) { codec = c }
        c.setCallback(callback, handler)
        c.configure(format, surface, null, 0)
        c.start()
        synchronized(lock) {
            started = true
            stall.reset(now())
        }
    }

    /**
     * Queues [unit]; non-keyframes are dropped until the first keyframe ([KeyframeGate]). "Until"
     * means admitted, not yet handed to the codec: a paced decoder holds the keyframe for the
     * latency, and the pictures that follow it meanwhile belong to its GOP.
     */
    open fun feed(unit: AccessUnit) {
        synchronized(lock) {
            if (released || !started) return
            if (!gate.admits(unit)) return
            if (pacer != null) {
                pacer.offer(unit, now())
            } else {
                if (pending.size >= MAX_PENDING) pending.pollFirst()
                pending.addLast(unit)
            }
        }
        drain()
    }

    /**
     * Moves the pacer's due pictures into [pending] and books one wake-up on the callback looper
     * for the next one that is not due yet: exactly one is ever pending, so an earlier due time
     * (a catch-up shifted the queue) replaces the wake-up already booked rather than adding a
     * second drain. Under [lock].
     */
    private fun admitDueLocked(nowMillis: Long) {
        val p = pacer ?: return
        while (true) {
            val unit = p.poll(nowMillis) ?: break
            if (pending.size >= MAX_PENDING) pending.pollFirst()
            pending.addLast(unit)
        }
        val next = p.nextDueMillis() ?: return
        if (wakeUpAt != null && wakeUpAt!! <= next) return
        if (wakeUpAt != null) handler.removeCallbacksAndMessages(token)
        wakeUpAt = next
        handler.postAtTime(wakeUp, token, uptimeFor(next))
    }

    private var wakeUpAt: Long? = null
    private val token = Any()
    private val wakeUp =
        Runnable {
            synchronized(lock) { wakeUpAt = null }
            drain()
        }

    /** The looper's uptime clock for a time on this class's monotonic clock; the two tick together. */
    private fun uptimeFor(nowMillis: Long): Long = SystemClock.uptimeMillis() + (nowMillis - now())

    /**
     * Drops every picture the codec holds and starts over at the next keyframe, keeping the codec
     * and its surface (the last picture stays on screen). The stream that follows may begin
     * anywhere: a seek landed somewhere else in the recording, a speed change hops windows. The
     * parameter sets are queued again before the first picture. Returns false when the codec
     * refused (it is then in an unknown state and the owner must replace it); true also for a
     * decoder that was never started or is already released, which has nothing to flush. The
     * next picture decoded is reported as a first frame again ([Listener.onFirstFrame]), so the
     * owner can show that it is waiting and when the wait is over.
     */
    open fun flush(): Boolean {
        synchronized(lock) {
            val c = codec ?: return true
            if (released || !started) return true
            pending.clear()
            pacer?.clear()
            freeInputs.clear()
            handler.removeCallbacksAndMessages(token)
            wakeUpAt = null
            gate.await()
            flushes++
            // Asynchronous mode: flush() leaves the codec stopped until start() is called again,
            // and every input buffer index handed out before is void.
            val ok = runCatching { c.flush() }.isSuccess && runCatching { c.start() }.isSuccess
            if (!ok) return false
            csdToSend = config.csd.size
            stall.reset(now())
            // The next picture out is a first frame again: the owner shows "connecting" until then.
            firstOutputSeen = false
            return true
        }
    }

    /** True when input is being accepted but nothing comes out ([StallDetector]); the owner recreates the codec. */
    open fun checkStall(nowMillis: Long): Boolean = synchronized(lock) { started && stall.isStalled(nowMillis) }

    /** Stops and releases the codec. Safe to call twice. */
    open fun release() {
        val c: MediaCodec?
        synchronized(lock) {
            if (released) return
            released = true
            c = codec
            codec = null
            pending.clear()
            pacer?.clear()
            freeInputs.clear()
            handler.removeCallbacksAndMessages(token)
        }
        runCatching { c?.stop() }
        runCatching { c?.release() }
    }

    private fun drain() {
        while (true) {
            // The copy stays under the lock: a release() on another thread frees the codec's
            // direct input buffers, and a put() into one of those after that is a native fault
            // (SIGSEGV in memcpy, seen when Back left fullscreen mid-frame), not an exception.
            synchronized(lock) {
                val c = codec ?: return
                if (released || !started) return
                val nowMillis = now()
                admitDueLocked(nowMillis)
                val index = takeInputIndex(freeInputs, csdToSend, pending.size) ?: return
                // An index the codec handed out before a flush is void; the picture it would have
                // carried must wait for a valid one, or the keyframe the gate just let through is
                // lost and every P-frame behind it is decoded against nothing.
                if (csdToSend > 0) {
                    val csd = config.csd[config.csd.size - csdToSend]
                    if (queueLocked(c, index, csd, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == Queued.YES) csdToSend--
                    return@synchronized // the loop comes back for the next buffer
                }
                if (pending.isEmpty()) return
                val unit = pending.pollFirst() ?: return
                val pts = unit.presentationTimeUs ?: 0L
                when (queueLocked(c, index, unit.data, pts, 0)) {
                    Queued.REFUSED -> {
                        pending.addFirst(unit)
                        return@synchronized
                    }
                    Queued.OVERSIZED -> {
                        dropOversizedLocked(unit, index)
                        return@synchronized
                    }
                    Queued.YES -> {
                        framesQueued++
                        stall.onInput(nowMillis)
                        recent.recordIn(pts, unit.data.size, unit.isKeyframe)
                    }
                }
            }
        }
    }

    private enum class Queued { YES, REFUSED, OVERSIZED }

    /**
     * Copies [data] into input buffer [index] and queues it; under [lock]. [Queued.REFUSED] when the
     * codec refused the index (void after a flush) or the buffer, in which case nothing was
     * consumed and the index is gone; [Queued.OVERSIZED] when [data] does not fit the buffer, in
     * which case nothing was queued and the index is still valid. A picture is never cut to fit:
     * the codec would decode the slices that arrived and conceal the rest (a correct top, a
     * smeared bottom), which is worse than a dropped picture.
     */
    private fun queueLocked(
        c: MediaCodec,
        index: Int,
        data: ByteArray,
        pts: Long,
        flags: Int,
    ): Queued {
        val buffer = runCatching { c.getInputBuffer(index) }.getOrNull() ?: return Queued.REFUSED
        buffer.clear()
        if (inputCapacity < 0) inputCapacity = buffer.remaining()
        if (!fits(data.size, buffer.remaining())) return Queued.OVERSIZED
        buffer.put(data, 0, data.size)
        return if (runCatching { c.queueInputBuffer(index, 0, data.size, pts, flags) }.isSuccess) {
            Queued.YES
        } else {
            Queued.REFUSED
        }
    }

    /**
     * [unit] is bigger than the codec's input buffers: it is dropped with everything queued behind
     * it, and the gate waits for the next keyframe, because the pictures that follow predict from
     * the one that was not decoded. The input buffer [index] goes back to the free list (it was
     * not used). Logged on the first drop and every [OVERSIZED_LOG_EVERY] after, so a recorder
     * whose pictures outgrow [maxInputSize] shows up in logcat without flooding it.
     */
    private fun dropOversizedLocked(
        unit: AccessUnit,
        index: Int,
    ) {
        oversized++
        freeInputs.addFirst(index)
        pending.clear()
        pacer?.clear()
        gate.await()
        if (oversized == 1 || oversized % OVERSIZED_LOG_EVERY == 0) {
            Log.w(
                TAG,
                "$codecName: picture of ${unit.data.size} bytes exceeds the input buffer ($inputCapacity), " +
                    "dropped $oversized",
            )
        }
    }

    private val callback =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(
                codec: MediaCodec,
                index: Int,
            ) {
                synchronized(lock) { if (!released) freeInputs.addLast(index) }
                drain()
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                val first: Boolean
                val show: Boolean
                synchronized(lock) {
                    if (released) return
                    val now = now()
                    stall.onOutput(now)
                    framesDecoded++
                    recent.recordOut(info.presentationTimeUs)
                    first = !firstOutputSeen
                    firstOutputSeen = true
                    // The first picture after a start or a flush is always shown; after that every
                    // Nth decoded one. A keyframe is not special here: what the viewer needs is
                    // an even cadence, and the next shown picture is at most N frames away.
                    show = first || framesDecoded % presentEvery == 0
                    if (show) {
                        framesPresented++
                        frameRate.onFrame(now)
                        outputGaps.onEvent(now)
                    }
                }
                if (!show) {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    return
                }
                // Rendered at (or just after) "now", not at the stream's presentation time: the
                // relay already paces the pictures, and a camera clock hours away from the TV's
                // uptime made SurfaceFlinger log an "implausible timestamp" line for every frame
                // of every tile (~50 lines/s, measurable in logd on the TV).
                val at = RenderPacer.presentAt(System.nanoTime(), renderSlotNanos)
                runCatching { codec.releaseOutputBuffer(index, at) }
                if (first) listener.onFirstFrame(this@TileDecoder)
            }

            override fun onError(
                codec: MediaCodec,
                e: MediaCodec.CodecException,
            ) {
                listener.onError(this@TileDecoder, e)
            }

            override fun onOutputFormatChanged(
                codec: MediaCodec,
                format: MediaFormat,
            ) = Unit
        }

    private fun now(): Long = System.nanoTime() / NANOS_PER_MILLI

    /** Tunables. */
    companion object {
        /** Drop the oldest frame beyond this many queued; keeps latency bounded when the CPU lags. */
        const val MAX_PENDING: Int = 30

        /**
         * The format the codec is configured with: the stream's mime type and parameter sets
         * (`csd-N`), a size hint, and `KEY_LOW_LATENCY` only when [lowLatency] (the codec declares
         * the feature, API 30+). The key is documented as ignored by decoders without the feature,
         * but an Amlogic Android 11 TV failed the grid's decoders while fullscreen worked, and the
         * unconditional key was one of the two things the grid did differently; see
         * [CodecNames.supportsLowLatency]. Pure, so the two cases are unit-tested.
         */
        fun inputFormat(
            config: CodecConfig,
            sizeHint: Pair<Int, Int> = DEFAULT_SIZE_HINT,
            lowLatency: Boolean,
            maxInputSize: Int = DEFAULT_MAX_INPUT_SIZE,
        ): MediaFormat {
            val format = MediaFormat.createVideoFormat(config.codec.mimeType, sizeHint.first, sizeHint.second)
            config.csd.forEachIndexed { i, bytes -> format.setByteBuffer("csd-$i", ByteBuffer.wrap(bytes)) }
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            return format
        }

        /**
         * `KEY_MAX_INPUT_SIZE` for a decoder built on a keyframe of [keyframeBytes]: twice that
         * keyframe (motion and scene changes make later ones bigger), at least
         * [DEFAULT_MAX_INPUT_SIZE] and at most [MAX_INPUT_SIZE]. Without the key a codec sizes its
         * input buffers from the format's width and height, which is only a hint here; a 1080p
         * HEVC keyframe of the main stream is 100 KB and more, so the buffers must be sized from
         * what the stream really sends, and [fits] drops rather than cuts a picture that still
         * does not fit.
         */
        fun inputSizeFor(keyframeBytes: Int): Int =
            (2L * keyframeBytes).coerceIn(DEFAULT_MAX_INPUT_SIZE.toLong(), MAX_INPUT_SIZE.toLong()).toInt()

        /** True when a picture of [size] bytes can be queued into an input buffer of [capacity] bytes. */
        fun fits(
            size: Int,
            capacity: Int,
        ): Boolean = size <= capacity

        /** The least `KEY_MAX_INPUT_SIZE` asked for: enough for any sub-stream picture and a 1080p keyframe. */
        const val DEFAULT_MAX_INPUT_SIZE: Int = 1 shl 20

        /** The most `KEY_MAX_INPUT_SIZE` asked for, so a corrupt size cannot make the codec allocate wildly. */
        const val MAX_INPUT_SIZE: Int = 8 shl 20

        private const val OVERSIZED_LOG_EVERY = 100
        private const val TAG = "TileDecoder"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val RECENT = 24
        private val DEFAULT_SIZE_HINT = 960 to 480

        /**
         * One looper for every decoder's callbacks. It lives as long as the process: a decoder
         * comes and goes with every focus change, and re-creating a thread each time cost more
         * than the idle thread does.
         */
        private val callbackThread: HandlerThread by lazy {
            HandlerThread("codec-callbacks").also { it.start() }
        }

        private fun callbackLooper(): Looper = callbackThread.looper
    }
}

/**
 * The input buffer [drain][TileDecoder] fills next, taken off [freeInputs], or null when there is
 * nothing to put in one ([csdLeft] parameter sets still to send, [pendingPictures] pictures
 * waiting). An index is only ever taken to be used: the codec hands each input buffer out once,
 * so one taken while nothing was waiting would be lost for good, and a decoder that loses its
 * handful of buffers that way stops taking pictures within a second.
 */
internal fun takeInputIndex(
    freeInputs: ArrayDeque<Int>,
    csdLeft: Int,
    pendingPictures: Int,
): Int? {
    if (freeInputs.isEmpty()) return null
    if (csdLeft <= 0 && pendingPictures == 0) return null
    return freeInputs.pollFirst()
}
