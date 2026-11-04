package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameRateMeterTest {
    private val meter = FrameRateMeter(windowMillis = 2_000)

    @Test
    fun `no frames is zero`() {
        assertThat(meter.fps(10_000)).isEqualTo(0f)
    }

    @Test
    fun `counts frames rendered in the window`() {
        for (t in 0 until 2_000 step 40) meter.onFrame(t.toLong()) // 25 fps
        assertThat(meter.fps(2_000)).isWithin(0.01f).of(25f)
        for (t in 2_000 until 3_000 step 40) meter.onFrame(t.toLong())
        assertThat(meter.fps(3_000)).isWithin(0.01f).of(25f)
    }

    @Test
    fun `old frames fall out of the window so a stall reads as a drop`() {
        for (t in 0 until 1_000 step 40) meter.onFrame(t.toLong())
        assertThat(meter.fps(1_000)).isWithin(0.01f).of(12.5f)
        assertThat(meter.fps(2_500)).isWithin(0.01f).of(6f) // only the 12 frames at 520..960 ms remain
        assertThat(meter.fps(3_000)).isEqualTo(0f)
    }

    @Test
    fun `one keyframe per second reads as one fps`() {
        meter.onFrame(0)
        meter.onFrame(1_000)
        assertThat(meter.fps(1_500)).isWithin(0.01f).of(1f)
    }

    @Test
    fun `reset forgets everything`() {
        meter.onFrame(100)
        meter.reset()
        assertThat(meter.fps(200)).isEqualTo(0f)
    }
}
