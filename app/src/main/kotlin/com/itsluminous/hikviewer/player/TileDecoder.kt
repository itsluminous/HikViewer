package com.itsluminous.hikviewer.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
 * (`releaseOutputBuffer(render = true)`): the relay already paces the stream at the camera's
 * frame rate and a live view wants the lowest latency. Decoding starts at the first keyframe so
 * a mid-GOP start never shows grey blocks. A watchdog ([checkStall]) reports a codec that accepts
 * input but produces nothing for [STALL_MILLIS]; the owner then recreates it.
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

    private val thread = HandlerThread("tile-decoder").also { it.start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val pending = ArrayDeque<AccessUnit>()
    private val freeInputs = ArrayDeque<Int>()
    private var codec: MediaCodec? = null
    private var released = false
    private var started = false
    private var inputAccepted = false
    private var firstOutputSeen = false
    private var lastOutputAtMillis = 0L
    private var lastInputAtMillis = 0L
    private val frameRate = FrameRateMeter()

    /** Frames queued and not yet handed to the codec; the lag signal. */
    open val queuedFrames: Int get() = synchronized(lock) { pending.size }

    /** True once a frame has been rendered. */
    val hasOutput: Boolean get() = synchronized(lock) { firstOutputSeen }

    /** Pictures rendered per second, measured from the codec's output callbacks. */
    open fun framesPerSecond(nowMillis: Long): Float = synchronized(lock) { frameRate.fps(nowMillis) }

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
            lastOutputAtMillis = System.nanoTime() / NANOS_PER_MILLI
        }
    }

    /** Queues [unit]; non-keyframes are dropped until the first keyframe. */
    open fun feed(unit: AccessUnit) {
        synchronized(lock) {
            if (released || !started) return
            if (!inputAccepted && !unit.isKeyframe) return
            if (pending.size >= MAX_PENDING) pending.pollFirst()
            pending.addLast(unit)
        }
        handler.post { drain() }
    }

    /**
     * True when input is being accepted but no picture came out for [STALL_MILLIS]: the codec is
     * wedged (seen on the MiTV after a resolution change) and must be recreated.
     */
    open fun checkStall(nowMillis: Long): Boolean = synchronized(lock) {
        started &&
            inputAccepted &&
            nowMillis - lastOutputAtMillis > STALL_MILLIS &&
            nowMillis - lastInputAtMillis < STALL_MILLIS
    }

    /** Stops and releases the codec and its thread. Safe to call twice. */
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
        thread.quitSafely()
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
                inputAccepted = true
                lastInputAtMillis = System.nanoTime() / NANOS_PER_MILLI
                val buffer = runCatching { c.getInputBuffer(index) }.getOrNull() ?: return
                buffer.clear()
                val size = minOf(unit.data.size, buffer.remaining())
                buffer.put(unit.data, 0, size)
                val pts = unit.presentationTimeUs ?: 0L
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
                    lastOutputAtMillis = System.nanoTime() / NANOS_PER_MILLI
                    frameRate.onFrame(lastOutputAtMillis)
                    first = !firstOutputSeen
                    firstOutputSeen = true
                }
                runCatching { codec.releaseOutputBuffer(index, true) }
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

    /** Tunables. */
    companion object {
        /** No output for this long while input is accepted → stalled. */
        const val STALL_MILLIS: Long = 3_000

        /** Drop the oldest frame beyond this many queued; keeps latency bounded when the CPU lags. */
        const val MAX_PENDING: Int = 30

        private const val NANOS_PER_MILLI = 1_000_000L
        private val DEFAULT_SIZE_HINT = 960 to 480
    }
}
