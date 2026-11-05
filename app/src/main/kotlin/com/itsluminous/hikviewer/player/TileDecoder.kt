package com.itsluminous.hikviewer.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
 * (`releaseOutputBuffer(render = true)`): the relay already paces the stream at the camera's
 * frame rate and a live view wants the lowest latency. Decoding starts at the first keyframe so
 * a mid-GOP start never shows grey blocks. A watchdog ([checkStall], rules in [StallDetector])
 * reports a codec that accepts input but produces nothing; the owner then recreates it.
 */
open class TileDecoder(
    /** `MediaCodec` name from [CodecNames]. */
    val codecName: String,
    private val config: CodecConfig,
    private val surface: Surface,
    private val listener: Listener,
    /** Width/height hint; the decoder reads the real size from the SPS. */
    private val sizeHint: Pair<Int, Int> = DEFAULT_SIZE_HINT,
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

    /** Frames queued and not yet handed to the codec; the lag signal. */
    open val queuedFrames: Int get() = synchronized(lock) { pending.size }

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
    private val recentIn = ArrayDeque<String>()
    private val recentOut = ArrayDeque<Long>()
    private var inputCapacity = -1
    private var truncated = 0

    /** Diagnostic: the last pictures fed (pts/size/K) and the last output timestamps. */
    fun recentHistory(): String = synchronized(lock) {
        "cap=$inputCapacity truncated=$truncated in[${recentIn.joinToString(" ")}] out[${recentOut.joinToString(" ")}]"
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

    /** Queues [unit]; non-keyframes are dropped until the first keyframe. */
    open fun feed(unit: AccessUnit) {
        synchronized(lock) {
            if (released || !started) return
            if (!stall.hasInput && !unit.isKeyframe) return
            if (pending.size >= MAX_PENDING) pending.pollFirst()
            pending.addLast(unit)
        }
        drain()
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
            freeInputs.clear()
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
                if (released || !started || freeInputs.isEmpty() || pending.isEmpty()) return
                val index = freeInputs.pollFirst()
                val unit = pending.pollFirst()
                framesQueued++
                stall.onInput(now())
                val buffer = runCatching { c.getInputBuffer(index) }.getOrNull() ?: return
                buffer.clear()
                if (inputCapacity < 0) inputCapacity = buffer.remaining()
                val size = minOf(unit.data.size, buffer.remaining())
                if (size < unit.data.size) truncated++
                buffer.put(unit.data, 0, size)
                val pts = unit.presentationTimeUs ?: 0L
                if (recentIn.size >= RECENT) recentIn.removeFirst()
                recentIn.addLast("${pts / 1000}/${unit.data.size}${if (unit.isKeyframe) "K" else ""}")
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
                    if (recentOut.size >= RECENT) recentOut.removeFirst()
                    recentOut.addLast(info.presentationTimeUs / 1000)
                    frameRate.onFrame(now)
                    first = !firstOutputSeen
                    firstOutputSeen = true
                }
                // Rendered "now", not at the stream's presentation time: the relay already paces
                // the pictures, and a camera clock hours away from the TV's uptime made
                // SurfaceFlinger log an "implausible timestamp" line for every frame of every tile
                // (~50 lines/s, measurable in logd on the TV).
                runCatching { codec.releaseOutputBuffer(index, System.nanoTime()) }
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
