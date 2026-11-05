package com.itsluminous.hikviewer.demo

import android.content.res.AssetManager
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.DecodePipeline
import com.itsluminous.hikviewer.core.stream.StreamStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The synthetic test patterns bundled with debug builds (`assets/demo/` MPEG-TS files: ffmpeg `testsrc2`,
 * `smptebars` and `testsrc`, 320x180, 12 fps, 2 s, Constrained Baseline H.264 in MPEG-TS), run
 * through `:core`'s own demuxer so the demo exercises the real decode path. Each clip is parsed
 * once and replayed in a loop with increasing presentation times, like a live stream.
 *
 * These clips exist so that screenshots and demos never contain footage from anyone's cameras.
 */
class DemoClips(
    private val assets: AssetManager,
) {
    private val clips: List<List<AccessUnit>> by lazy {
        checkNotNull(assets.list(ASSET_DIR)) { "no $ASSET_DIR assets" }
            .filter { it.endsWith(".ts") }
            .sorted()
            .map { name -> parse(assets.open("$ASSET_DIR/$name").use { it.readBytes() }) }
            .also { check(it.isNotEmpty()) { "no clips under $ASSET_DIR" } }
    }

    /** Number of distinct patterns. */
    val count: Int get() = clips.size

    /**
     * Endless paced replay of clip [index] (modulo [count], so any channel number works). When
     * [paced] is off frames are emitted as fast as they are collected, which playback scrubbing
     * relies on to reach a position quickly.
     */
    fun stream(
        index: Int,
        onStatus: (StreamStatus) -> Unit = {},
        paced: Boolean = true,
    ): Flow<AccessUnit> = flow {
        val pictures = clips[Math.floorMod(index, clips.size)]
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

    private companion object {
        const val ASSET_DIR = "demo"

        /** 12 fps, the recorder sub-stream rate the clips were rendered at. */
        const val FRAME_PERIOD_MILLIS = 83L
        const val MICROS_PER_MILLI = 1_000L
        const val TS_PACKET = 188
        const val PACKETS_PER_CHUNK = 7
    }
}
