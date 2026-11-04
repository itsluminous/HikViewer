package com.itsluminous.hikviewer.player

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.CodecConfig
import java.nio.ByteBuffer

/**
 * A few decoders shared by every keyframe-only tile.
 *
 * An open decoder costs ~17 MB whether it decodes 12 or 1 frame per second, so slideshow tiles must
 * not own one each. Instead each keyframe is decoded as a self-contained job: switch the codec's
 * output to the tile's surface (`setOutputSurface`, API 23), queue the parameter sets, the IDR
 * picture and an end-of-stream marker, render the one picture that comes out, flush. Twelve tiles
 * at one keyframe per second are ~12 jobs/s, far below what two decoders manage, and no YUV→Bitmap
 * copy is needed because the picture lands on the tile's own `SurfaceView` like a live frame.
 *
 * Jobs are keyed by tile; a newer keyframe replaces a queued one for the same tile, so a slow
 * device shows fresh pictures instead of a backlog. The synchronous `MediaCodec` API is used here
 * deliberately: one job is a strict queue → drain sequence on the worker's own thread.
 */
open class KeyframeDecoderPool(
    maxDecoders: Int = MAX_DECODERS,
    private val pickCodec: (mimeType: String) -> String? = { CodecNames.pick(it, software = true) },
) {
    private val workers = List(maxDecoders.coerceIn(1, MAX_DECODERS)) { Worker("keyframe-$it") }

    /** Decodes [unit] (a keyframe carrying its [CodecConfig]) onto [surface] on behalf of [tile]. */
    open fun submit(
        tile: Any,
        surface: Surface,
        unit: AccessUnit,
    ) {
        val config = unit.config ?: return
        if (!unit.isKeyframe) return
        val worker = workers[(tile.hashCode() and Int.MAX_VALUE) % workers.size]
        worker.enqueue(Job(tile, surface, unit, config))
    }

    /** Drops queued work for [tile]. */
    fun cancel(tile: Any) = workers.forEach { it.cancel(tile) }

    /** Releases every decoder. */
    fun release() = workers.forEach { it.release() }

    private class Job(
        val tile: Any,
        val surface: Surface,
        val unit: AccessUnit,
        val config: CodecConfig,
    )

    private inner class Worker(
        name: String,
    ) {
        private val thread = HandlerThread(name).also { it.start() }
        private val handler = Handler(thread.looper)
        private val queue = LinkedHashMap<Any, Job>()
        private var codec: MediaCodec? = null
        private var codecConfig: CodecConfig? = null
        private var released = false

        fun enqueue(job: Job) {
            synchronized(queue) {
                if (released) return
                queue.remove(job.tile)
                queue[job.tile] = job
            }
            handler.post { runOne() }
        }

        fun cancel(tile: Any) = synchronized(queue) { queue.remove(tile) }

        fun release() {
            synchronized(queue) {
                released = true
                queue.clear()
            }
            handler.post { closeCodec() }
            thread.quitSafely()
        }

        private fun runOne() {
            val job = synchronized(queue) { queue.keys.firstOrNull()?.let(queue::remove) } ?: return
            if (!job.surface.isValid) return
            try {
                decode(job)
            } catch (e: MediaCodec.CodecException) {
                Log.w(TAG, "keyframe decode failed (${e.diagnosticInfo}); recreating decoder")
                closeCodec()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "keyframe decoder in a bad state; recreating", e)
                closeCodec()
            } catch (e: IllegalArgumentException) {
                // The tile's surface was released between the validity check and configure /
                // setOutputSurface (the grid went to the background); the job is simply dropped.
                Log.w(TAG, "keyframe surface vanished: ${e.message}")
                closeCodec()
            }
        }

        private fun decode(job: Job) {
            val c = codecFor(job)
            c.setOutputSurface(job.surface)
            queueInput(c, job.config.csd.fold(ByteArray(0)) { acc, b -> acc + b }, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            queueInput(c, job.unit.data, 0)
            queueInput(c, ByteArray(0), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val info = MediaCodec.BufferInfo()
            var rendered = false
            val deadline = System.nanoTime() + JOB_TIMEOUT_NANOS
            while (System.nanoTime() < deadline) {
                val index = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val render = !rendered && info.size > 0
                    c.releaseOutputBuffer(index, render)
                    rendered = rendered || render
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            c.flush()
        }

        private fun queueInput(
            c: MediaCodec,
            data: ByteArray,
            flags: Int,
        ) {
            val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
            check(index >= 0) { "no input buffer" }
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
            val name = checkNotNull(pickCodec(mime)) { "no decoder for $mime" }
            val format = MediaFormat.createVideoFormat(mime, SIZE_HINT_WIDTH, SIZE_HINT_HEIGHT)
            job.config.csd.forEachIndexed { i, bytes -> format.setByteBuffer("csd-$i", ByteBuffer.wrap(bytes)) }
            val c = MediaCodec.createByCodecName(name)
            c.configure(format, job.surface, null, 0)
            c.start()
            codec = c
            codecConfig = job.config
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
    }
}
