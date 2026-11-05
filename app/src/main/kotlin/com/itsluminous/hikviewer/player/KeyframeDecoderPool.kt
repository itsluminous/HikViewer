package com.itsluminous.hikviewer.player

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.CodecConfig
import java.nio.ByteBuffer

/**
 * A few decoders shared by every keyframe-only tile.
 *
 * An open decoder costs ~17 MB whether it decodes 12 or 1 frame per second, so slideshow tiles must
 * not own one each. Instead each keyframe is decoded as a self-contained job: queue the parameter
 * sets, the IDR picture and an end-of-stream marker, take the one picture that comes out as a
 * YUV buffer, scale and convert it to the tile's size ([YuvToRgb]) and hand the pixels to the
 * tile's [StillSink], flush. Twelve tiles at one keyframe per second are ~12 jobs/s, far below
 * what two decoders manage.
 *
 * Until round 3 the pool rendered onto the tile's own surface, re-pointing the codec with
 * `setOutputSurface` twice per job. On the Android 10 TV that meant ~24 BufferQueue connects a
 * second, five framework log lines each (`logd` at 61 % CPU), a native heap growing 4 MB/min in the
 * OMX client, and a surface the tile's live decoder could not open until the pool let go. Decoding
 * to buffers touches no surface at all: the codec is configured once per worker and keeps running.
 *
 * Jobs are routed by their [CodecConfig], so a worker sees one configuration and never rebuilds
 * its codec for an alternating pair of tiles (two cameras with different parameter sets used to
 * cost a create/configure/start/stop/release per job). A newer keyframe replaces a queued one for
 * the same tile, so a slow device shows fresh pictures instead of a backlog. The synchronous
 * `MediaCodec` API is used here deliberately: one job is a strict queue → drain sequence on the
 * worker's own thread.
 */
open class KeyframeDecoderPool(
    maxDecoders: Int = MAX_DECODERS,
    private val pickCodec: (mimeType: String) -> String? = { CodecNames.pick(it, software = true) },
) {
    private val workers = List(maxDecoders.coerceIn(1, MAX_DECODERS)) { Worker("keyframe-$it") }

    /** Decodes [unit] (a keyframe carrying its [CodecConfig]) into [sink] on behalf of [tile]. */
    open fun submit(
        tile: Any,
        sink: StillSink,
        unit: AccessUnit,
    ) {
        val config = unit.config ?: return
        if (!unit.isKeyframe) return
        workerFor(config).enqueue(Job(tile, sink, unit, config))
    }

    /** Drops queued work for [tile]. */
    fun cancel(tile: Any) = workers.forEach { it.cancel(tile) }

    /**
     * Closes the decoders but keeps the workers, so the pool can serve again after a restart.
     * Called when playback stops: an idle software decoder still holds ~17 MB in `media.codec`
     * and a looper thread, which a backgrounded app must not keep.
     */
    open fun closeCodecs() = workers.forEach { it.closeCodecs() }

    /** Releases every decoder and worker thread. */
    fun release() = workers.forEach { it.release() }

    /** Codecs the workers built so far (one per worker in steady state); the TV logcat shows churn through it. */
    val codecsCreated: Int get() = workers.sumOf { it.codecsCreated }

    /** Index of the worker that handles [config]; stable per configuration. */
    fun workerIndexFor(config: CodecConfig): Int = (config.hashCode() and Int.MAX_VALUE) % workers.size

    private fun workerFor(config: CodecConfig) = workers[workerIndexFor(config)]

    /** The codec offered no input buffer within [INPUT_TIMEOUT_US]. */
    private class InputStarved : RuntimeException("no input buffer")

    private class Job(
        val tile: Any,
        val sink: StillSink,
        val unit: AccessUnit,
        val config: CodecConfig,
    )

    private inner class Worker(
        private val name: String,
    ) {
        private val queue = LinkedHashMap<Any, Job>()
        private var codec: MediaCodec? = null
        private var codecConfig: CodecConfig? = null
        private var released = false
        private var pixels = IntArray(0)
        private var starved = 0
        private var jobs = 0
        private var undelivered = 0
        private var unsized = 0

        @Volatile var codecsCreated: Int = 0
            private set

        // The thread starts with the first job, not with the pool: a single-stream screen
        // (recorded playback, the phone camera view) builds a pool it never submits to, and a
        // thread per such screen would outlive it, since nothing owns the pool to release it.
        private var thread: HandlerThread? = null
        private var handler: Handler? = null

        fun enqueue(job: Job) {
            val h =
                synchronized(queue) {
                    if (released) return
                    queue.remove(job.tile)
                    queue[job.tile] = job
                    handlerLocked()
                }
            h.post { runOne() }
        }

        fun cancel(tile: Any) = synchronized(queue) { queue.remove(tile) }

        fun closeCodecs() {
            val h =
                synchronized(queue) {
                    queue.clear()
                    handler
                }
            h?.post { closeCodec() }
        }

        fun release() {
            val (h, t) =
                synchronized(queue) {
                    released = true
                    queue.clear()
                    handler to thread
                }
            h?.post { closeCodec() }
            t?.quitSafely()
        }

        /** Started on demand; call with the queue lock held. */
        private fun handlerLocked(): Handler = handler ?: run {
            val t = HandlerThread(name).also { it.start() }
            thread = t
            Handler(t.looper).also { handler = it }
        }

        private fun runOne() {
            val job = synchronized(queue) { queue.keys.firstOrNull()?.let(queue::remove) } ?: return
            val width = job.sink.stillWidth
            val height = job.sink.stillHeight
            if (width <= 0 || height <= 0) {
                // The tile has no size yet; the next keyframe will do. Logged because a tile that
                // never gets a size is a blank tile forever, which looks like a decoder failure.
                if (++unsized % UNSIZED_LOG_EVERY == 1) {
                    Log.i(TAG, "$name: ${job.sink} has no size yet ($unsized skipped)")
                }
                return
            }
            try {
                val delivered = decode(job, width, height)
                starved = 0
                jobs++
                if (!delivered) {
                    undelivered++
                    Log.w(TAG, "$name: job #$jobs for ${job.sink} gave no picture in time ($undelivered so far)")
                } else if (jobs <= FIRST_JOBS_LOGGED || jobs % JOBS_LOG_EVERY == 0) {
                    Log.i(TAG, "$name: job #$jobs: ${width}x$height still to ${job.sink} ($undelivered undelivered)")
                }
            } catch (_: InputStarved) {
                // A freshly started software decoder can take longer than the input timeout to
                // offer its first buffer; one slow job is dropped, only a codec that keeps starving
                // its input is rebuilt (rebuilding is the churn this pool exists to avoid).
                if (++starved >= STARVED_JOBS_BEFORE_REBUILD) {
                    Log.w(TAG, "$name: no input buffer $starved times in a row; recreating decoder")
                    closeCodec()
                    starved = 0
                }
            } catch (e: MediaCodec.CodecException) {
                Log.w(TAG, "$name: keyframe decode failed (${e.diagnosticInfo}); recreating decoder")
                closeCodec()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "$name: keyframe decoder in a bad state; recreating", e)
                closeCodec()
            }
        }

        /** True when a picture reached the sink. */
        private fun decode(
            job: Job,
            width: Int,
            height: Int,
        ): Boolean {
            val c = codecFor(job)
            try {
                val csd = job.config.csd.fold(ByteArray(0)) { acc, b -> acc + b }
                queueInput(c, csd, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                queueInput(c, job.unit.data, 0)
                queueInput(c, ByteArray(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return drainOnePicture(c) { image -> deliver(image, job.sink, width, height) }
            } finally {
                c.flush()
            }
        }

        /**
         * Hands the first picture that comes out to [onPicture] and swallows the rest up to
         * end-of-stream. Returns whether [onPicture] accepted one.
         */
        private fun drainOnePicture(
            c: MediaCodec,
            onPicture: (Image) -> Boolean,
        ): Boolean {
            val info = MediaCodec.BufferInfo()
            var delivered = false
            val deadline = System.nanoTime() + JOB_TIMEOUT_NANOS
            while (System.nanoTime() < deadline) {
                val index = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index < 0) continue
                if (!delivered && info.size > 0) {
                    c.getOutputImage(index)?.use { image -> delivered = onPicture(image) }
                }
                c.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            return delivered
        }

        /** False when the decoder's picture is not a usable YUV image (logged: a blank tile has a reason). */
        private fun deliver(
            image: Image,
            sink: StillSink,
            width: Int,
            height: Int,
        ): Boolean {
            val crop = image.cropRect
            val planes = image.planes
            if (planes.size < YUV_PLANES || crop.width() <= 0 || crop.height() <= 0) {
                Log.w(TAG, "$name: unusable picture: ${planes.size} planes, crop=$crop, format=${image.format}")
                return false
            }
            if (pixels.size < width * height) pixels = IntArray(width * height)

            fun plane(i: Int) = YuvPlane(planes[i].buffer, planes[i].rowStride, planes[i].pixelStride)
            // The crop offset is folded in by slicing each plane's buffer at the crop origin.
            val y = plane(0).let { p -> p.offset(crop.top * p.rowStride + crop.left * p.pixelStride) }
            val u = plane(1).let { p -> p.offset((crop.top shr 1) * p.rowStride + (crop.left shr 1) * p.pixelStride) }
            val v = plane(2).let { p -> p.offset((crop.top shr 1) * p.rowStride + (crop.left shr 1) * p.pixelStride) }
            YuvToRgb.convert(y, u, v, crop.width(), crop.height(), width, height, pixels)
            sink.onStill(pixels, width, height)
            return true
        }

        private fun YuvPlane.offset(bytes: Int): YuvPlane {
            if (bytes == 0) return this
            val sliced = buffer.duplicate()
            sliced.position(bytes)
            return YuvPlane(sliced.slice(), rowStride, pixelStride)
        }

        private fun queueInput(
            c: MediaCodec,
            data: ByteArray,
            flags: Int,
        ) {
            val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) throw InputStarved()
            val buffer = checkNotNull(c.getInputBuffer(index)) { "null input buffer" }
            buffer.clear()
            val size = minOf(data.size, buffer.remaining())
            buffer.put(data, 0, size)
            c.queueInputBuffer(index, 0, size, 0, flags)
        }

        private fun codecFor(job: Job): MediaCodec {
            codec?.let { if (codecConfig == job.config) return it }
            closeCodec()
            val mime = job.config.codec.mimeType
            val codecName = checkNotNull(pickCodec(mime)) { "no decoder for $mime" }
            val format = MediaFormat.createVideoFormat(mime, SIZE_HINT_WIDTH, SIZE_HINT_HEIGHT)
            job.config.csd.forEachIndexed { i, bytes -> format.setByteBuffer("csd-$i", ByteBuffer.wrap(bytes)) }
            // Buffer output in the flexible YUV layout every decoder must support (API 21+).
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createByCodecName(codecName)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            codecConfig = job.config
            codecsCreated++
            Log.i(TAG, "$name: $mime via $codecName (codec #$codecsCreated)")
            return c
        }

        private fun closeCodec() {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
            codecConfig = null
        }
    }

    /** Limits. */
    companion object {
        /** Two shared decoders cover 16 tiles at one keyframe per second with room to spare. */
        const val MAX_DECODERS: Int = 2
        private const val TAG = "KeyframePool"
        private const val INPUT_TIMEOUT_US = 200_000L
        private const val DEQUEUE_TIMEOUT_US = 50_000L
        private const val JOB_TIMEOUT_NANOS = 700_000_000L
        private const val SIZE_HINT_WIDTH = 960
        private const val SIZE_HINT_HEIGHT = 480
        private const val YUV_PLANES = 3
        private const val STARVED_JOBS_BEFORE_REBUILD = 3
        private const val FIRST_JOBS_LOGGED = 20
        private const val JOBS_LOG_EVERY = 100
        private const val UNSIZED_LOG_EVERY = 10
    }
}
