package com.itsluminous.hikviewer.player

import android.graphics.SurfaceTexture
import android.view.Surface
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

    private fun keyframe(): AccessUnit {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val config = CodecConfig(VideoCodec.H264, null, sps, pps)
        return AccessUnit(VideoCodec.H264, idr, 0L, isKeyframe = true, config = config)
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
        pool.submit("tile", Surface(SurfaceTexture(1)), keyframe())
        val started = poolThreads().filter { it.name == "keyframe-0" }
        assertThat(started).hasSize(1)
        pool.release()
        started.single().join(5_000)
        assertThat(poolThreads()).hasSize(before)
    }
}
