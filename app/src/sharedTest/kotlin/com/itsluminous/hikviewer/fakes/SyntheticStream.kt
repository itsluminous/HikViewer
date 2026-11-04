package com.itsluminous.hikviewer.fakes

import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.DecodePipeline
import com.itsluminous.hikviewer.core.stream.StreamStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Pictures from the committed 2.5 s MPEG-TS capture (`ch1_sub_2s5.ts`, clear H.264 960x480), run
 * through `:core`'s own demux → assemble pipeline so UI tests exercise real access units without a
 * network. The capture is replayed in a loop at [FRAME_PERIOD_MILLIS]; presentation times keep
 * increasing so a decoder never sees time go backwards.
 */
object SyntheticStream {
    /** 12 fps, like the recorder's sub-stream. */
    const val FRAME_PERIOD_MILLIS: Long = 83

    private const val TS_PACKET = 188
    private const val PACKETS_PER_CHUNK = 7

    /** The decoded capture, parsed once. */
    val pictures: List<AccessUnit> by lazy { parse(fixtureBytes()) }

    /** Endless paced replay; [onStatus] sees Connecting then Streaming. */
    fun flow(
        onStatus: (StreamStatus) -> Unit = {},
        paced: Boolean = true,
    ): Flow<AccessUnit> = flow {
        onStatus(StreamStatus.Connecting(1))
        var loop = 0L
        val span = (pictures.last().presentationTimeUs ?: 0L) + FRAME_PERIOD_MILLIS * 1000
        while (true) {
            for ((i, p) in pictures.withIndex()) {
                if (loop == 0L && i == 0) onStatus(StreamStatus.Streaming)
                emit(AccessUnit(p.codec, p.data, (p.presentationTimeUs ?: 0L) + loop * span, p.isKeyframe, p.config))
                if (paced) delay(FRAME_PERIOD_MILLIS)
            }
            loop++
        }
    }

    private fun parse(ts: ByteArray): List<AccessUnit> {
        val pipeline = DecodePipeline(null)
        val out = ArrayList<AccessUnit>()
        var offset = 0
        while (offset < ts.size) {
            val end = minOf(offset + TS_PACKET * PACKETS_PER_CHUNK, ts.size)
            out += pipeline.process(ts.copyOfRange(offset, end))
            offset = end
        }
        check(out.any { it.isKeyframe && it.config != null }) { "fixture produced no keyframe with parameter sets" }
        return out
    }

    private fun fixtureBytes(): ByteArray =
        checkNotNull(SyntheticStream::class.java.getResourceAsStream("/fixtures/ch1_sub_2s5.ts")) {
            "fixture ch1_sub_2s5.ts missing from the test resources"
        }.use { it.readBytes() }
}
