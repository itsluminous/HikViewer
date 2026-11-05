package com.itsluminous.hikviewer.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.CodecConfig
import com.itsluminous.hikviewer.core.stream.VideoCodec
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pool's worker threads must not exist before the first keyframe job: single-stream screens
 * build a pool they never use and nothing releases it, so an eager thread would leak per screen.
 */
@RunWith(AndroidJUnit4::class)
class KeyframeDecoderPoolTest {
    private fun poolThreads() = Thread.getAllStackTraces().keys.filter { it.name.startsWith("keyframe-") && it.isAlive }

    private fun keyframe(spsTail: Byte = 0): AccessUnit {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, spsTail)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val config = CodecConfig(VideoCodec.H264, null, sps, pps)
        return AccessUnit(VideoCodec.H264, idr, 0L, isKeyframe = true, config = config)
    }

    private val sink =
        object : StillSink {
            override val stillWidth = 160
            override val stillHeight = 90

            override fun onStill(
                pixels: IntArray,
                width: Int,
                height: Int,
            ) = Unit
        }

    @Test
    fun `a pool nobody submits to owns no thread, and closing or releasing it is harmless`() {
        val before = poolThreads().size
        val pool = KeyframeDecoderPool(maxDecoders = 2, pickCodec = { null })
        assertThat(poolThreads()).hasSize(before)
        pool.closeCodecs()
        pool.release()
        assertThat(poolThreads()).hasSize(before)
    }

    @Test
    fun `the first job starts the worker and release quits it`() {
        val before = poolThreads().size
        // No decoder name: the job fails inside the worker (caught) but the thread must have started.
        val pool = KeyframeDecoderPool(maxDecoders = 1, pickCodec = { null })
        pool.submit("tile", sink, keyframe())
        val started = poolThreads().filter { it.name == "keyframe-0" }
        assertThat(started).hasSize(1)
        pool.release()
        started.single().join(5_000)
        assertThat(poolThreads()).hasSize(before)
    }

    @Test
    fun `jobs are routed by codec configuration, not by tile, so a worker keeps one codec`() {
        val pool = KeyframeDecoderPool(maxDecoders = 2, pickCodec = { null })
        val a = keyframe(spsTail = 1).config!!
        val sameAsA = keyframe(spsTail = 1).config!!
        val b = keyframe(spsTail = 2).config!!
        assertThat(pool.workerIndexFor(a)).isEqualTo(pool.workerIndexFor(sameAsA))
        assertThat(pool.workerIndexFor(a)).isIn(0 until 2)
        assertThat(pool.workerIndexFor(b)).isIn(0 until 2)
        // Twenty tiles with the same configuration all land on one worker, whatever their identity.
        val indexes = (1..20).map { pool.workerIndexFor(keyframe(spsTail = 1).config!!) }.toSet()
        assertThat(indexes).hasSize(1)
        pool.release()
    }

    @Test
    fun `a sink without a size is skipped and no decoder is asked for`() {
        var asked = 0
        val pool =
            KeyframeDecoderPool(maxDecoders = 1, pickCodec = {
                asked++
                null
            })
        val unsized =
            object : StillSink {
                override val stillWidth = 0
                override val stillHeight = 0

                override fun onStill(
                    pixels: IntArray,
                    width: Int,
                    height: Int,
                ) = Unit
            }
        pool.submit("tile", unsized, keyframe())
        poolThreads().single { it.name == "keyframe-0" }.let { t ->
            pool.release()
            t.join(5_000)
        }
        assertThat(asked).isEqualTo(0)
        assertThat(pool.codecsCreated).isEqualTo(0)
    }
}
