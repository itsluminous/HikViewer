package com.itsluminous.hikviewer.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
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
 * measure of decode lag for [LiveBudget]. A fed picture is copied into a free input buffer on
 * the feeding thread itself; only the codec's callbacks run on the one looper thread every
 * decoder in the process shares. A thread hop per frame and a looper thread per tile were
 * measurable on the 1 GB TV with twelve tiles and bought nothing: the copy is thread-agnostic
 * and the callbacks are a few microseconds each. Pictures are rendered as soon as they are decoded
 * (`releaseOutputBuffer(render = true)`), so when a picture is shown is decided by when it is
 * fed: a [paced] decoder (the software tiles) holds each picture in a [FramePacer] until its
 * presentation time and feeds it from the callback looper, so the screen shows the pictures
 * evenly rather than with the relay's jitter; an unpaced one (the hardware hero and fullscreen)
 * feeds at once for the lowest latency. Decoding starts at the first keyframe so a mid-GOP start
 * never shows grey blocks. A watchdog ([checkStall], rules in [StallDetector]) reports a codec
 * that accepts input but produces nothing; the owner then recreates it.
 */
open class TileDecoder(
    /** `MediaCodec` name from [CodecNames]. */
    val codecName: String,
    private val config: CodecConfig,
    private val surface: Surface,
    private val listener: Listener,
    /** Width/height hint; the decoder reads the real size from the SPS. */
    private val sizeHint: Pair<Int, Int> = DEFAULT_SIZE_HINT,
    /** The jitter buffer, or null to feed every picture the moment it arrives. */
    private val pacer: FramePacer? = null,
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

    /** Pictures the codec produced so far. */
    val framesOut: Int get() = synchronized(lock) { framesRendered }

    private var framesQueued = 0
    private var framesRendered = 0
    private var keyframeAdmitted = false

    /**
     * Pictures are shown at the next multiple of this many nanoseconds instead of at once, so
     * every decoder in the process that shares the value lands its pictures in the same display
     * frames; 0 renders immediately. See [RenderPacer]: on the MiTV eight live tiles at 12 fps
     * each used to wake SurfaceFlinger for ~60 GPU compositions a second, one per tile per
     * picture, and the UI's own frames (focus border, top bar) queued behind them.
     */
    @Volatile var renderSlotNanos: Long = 0L

    private val recent = RecentPictures(RECENT)
    private val outputGaps = IntervalStats()
    private var inputCapacity = -1
    private var truncated = 0

    /** Diagnostic: the last pictures fed (pts/size, `K` for a keyframe) and the last output timestamps. */
    fun recentHistory(): String = synchronized(lock) { "cap=$inputCapacity truncated=$truncated ${recent.describe()}" }

    /** Diagnostic: the jitter buffer's fill and its re-base counters, or an empty string for an unpaced decoder. */
    fun describePacer(): String = synchronized(lock) {
        val p = pacer ?: return ""
        " paced=${p.size} rebuffers=${p.rebuffers} catchUps=${p.catchUps} dropped=${p.dropped}"
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
        val format = MediaFormat.createVideoFormat(config.codec.mimeType, sizeHint.first, sizeHint.second)
        config.csd.forEachIndexed { i, bytes -> format.setByteBuffer("csd-$i", ByteBuffer.wrap(bytes)) }
        // Ask for low latency where the platform supports it (API 30+); older decoders ignore the key.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
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
     * Queues [unit]; non-keyframes are dropped until the first keyframe. "Until" means admitted,
     * not yet handed to the codec: a paced decoder holds the keyframe for the latency, and the
     * pictures that follow it meanwhile belong to its GOP.
     */
    open fun feed(unit: AccessUnit) {
        synchronized(lock) {
            if (released || !started) return
            if (!keyframeAdmitted) {
                if (!unit.isKeyframe) return
                keyframeAdmitted = true
            }
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
     * for the next one that is not due yet. Under [lock].
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
                if (freeInputs.isEmpty() || pending.isEmpty()) return
                val index = freeInputs.pollFirst()
                val unit = pending.pollFirst()
                framesQueued++
                stall.onInput(nowMillis)
                val buffer = runCatching { c.getInputBuffer(index) }.getOrNull() ?: return
                buffer.clear()
                if (inputCapacity < 0) inputCapacity = buffer.remaining()
                val size = minOf(unit.data.size, buffer.remaining())
                if (size < unit.data.size) truncated++
                buffer.put(unit.data, 0, size)
                val pts = unit.presentationTimeUs ?: 0L
                recent.recordIn(pts, unit.data.size, unit.isKeyframe)
                runCatching { c.queueInputBuffer(index, 0, size, pts, 0) }
            }
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
                synchronized(lock) {
                    if (released) return
                    val now = now()
                    stall.onOutput(now)
                    framesRendered++
                    recent.recordOut(info.presentationTimeUs)
                    frameRate.onFrame(now)
                    outputGaps.onEvent(now)
                    first = !firstOutputSeen
                    firstOutputSeen = true
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
