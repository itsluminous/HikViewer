package com.itsluminous.hikviewer.demo

import android.content.res.AssetManager
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.DecodePipeline
import com.itsluminous.hikviewer.core.stream.StreamStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The synthetic clips bundled with debug builds (`assets/demo/<slug>.ts`: stock pictures of empty
 * rooms given a slow zoom by `scripts/gen-demo-clips.sh`, 640x360, 12 fps, 3 s, Constrained
 * Baseline H.264 in MPEG-TS), run through `:core`'s own demuxer so the demo exercises the real
 * decode path. Each clip is parsed once and replayed in a loop with increasing presentation times,
 * like a live stream.
 *
 * A clip is named after the demo camera it stands for ([slug] of "Front Door" is `front_door`),
 * so every demo tile shows the room its label names. A camera with no clip of its own (the names
 * past the twelve stills, or "Camera N") gets one by channel number, modulo the count.
 *
 * These clips exist so that screenshots and demos never contain footage from anyone's cameras.
 */
class DemoClips(
    private val assets: AssetManager,
) {
    /** Clip slug to its parsed pictures, in slug order. */
    private val clips: Map<String, List<AccessUnit>> by lazy {
        checkNotNull(assets.list(ASSET_DIR)) { "no $ASSET_DIR assets" }
            .filter { it.endsWith(CLIP_SUFFIX) }
            .sorted()
            .associate { name ->
                name.removeSuffix(CLIP_SUFFIX) to parse(assets.open("$ASSET_DIR/$name").use { it.readBytes() })
            }.also { check(it.isNotEmpty()) { "no clips under $ASSET_DIR" } }
    }

    private val byIndex: List<List<AccessUnit>> by lazy { clips.values.toList() }

    /** Number of distinct clips. */
    val count: Int get() = clips.size

    /** The clip slugs that exist, sorted. */
    val slugs: List<String> get() = clips.keys.toList()

    /**
     * Endless paced replay of the clip for camera [name] (its [slug], else clip [index] modulo
     * [count], so any channel number works). When [paced] is off frames are emitted as fast as they
     * are collected, which playback scrubbing relies on to reach a position quickly.
     */
    fun stream(
        name: String,
        index: Int,
        onStatus: (StreamStatus) -> Unit = {},
        paced: Boolean = true,
    ): Flow<AccessUnit> = flow {
        val pictures = clips[slug(name)] ?: byIndex[Math.floorMod(index, byIndex.size)]
        onStatus(StreamStatus.Connecting(1))
        val span = (pictures.last().presentationTimeUs ?: 0L) + FRAME_PERIOD_MILLIS * MICROS_PER_MILLI
        var loop = 0L
        while (true) {
            for ((i, picture) in pictures.withIndex()) {
                if (loop == 0L && i == 0) onStatus(StreamStatus.Streaming)
                emit(
                    AccessUnit(
                        picture.codec,
                        picture.data,
                        (picture.presentationTimeUs ?: 0L) + loop * span,
                        picture.isKeyframe,
                        picture.config,
                    ),
                )
                if (paced) delay(FRAME_PERIOD_MILLIS)
            }
            loop++
        }
    }

    private fun parse(ts: ByteArray): List<AccessUnit> {
        val pipeline = DecodePipeline(verificationCode = null)
        val out = ArrayList<AccessUnit>()
        var offset = 0
        while (offset < ts.size) {
            val end = minOf(offset + TS_PACKET * PACKETS_PER_CHUNK, ts.size)
            out += pipeline.process(ts.copyOfRange(offset, end))
            offset = end
        }
        check(out.any { it.isKeyframe && it.config != null }) { "demo clip has no keyframe with parameter sets" }
        return out
    }

    companion object {
        /** The clip file name (without `.ts`) for camera [name]: lower case, spaces to underscores. */
        fun slug(name: String): String = name.trim().lowercase().replace(' ', '_')

        private const val ASSET_DIR = "demo"
        private const val CLIP_SUFFIX = ".ts"

        /** 12 fps, the recorder sub-stream rate the clips were rendered at. */
        private const val FRAME_PERIOD_MILLIS = 83L
        private const val MICROS_PER_MILLI = 1_000L
        private const val TS_PACKET = 188
        private const val PACKETS_PER_CHUNK = 7
    }
}
