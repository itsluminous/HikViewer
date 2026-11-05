package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StallDetectorTest {
    @Test
    fun `no input means no stall however long the silence`() {
        val d = StallDetector(0)
        assertThat(d.isStalled(10_000)).isFalse()
        assertThat(d.inputAgeMillis(10_000)).isEqualTo(Long.MAX_VALUE)
        assertThat(d.outputAgeMillis(10_000)).isEqualTo(10_000)
    }

    @Test
    fun `a healthy 12 fps decoder is never stalled`() {
        val d = StallDetector(0)
        var t = 0L
        repeat(120) {
            t += 83
            d.onInput(t)
            d.onOutput(t + 40) // ~half a frame of decode latency
            assertThat(d.isStalled(t + 80)).isFalse()
        }
        assertThat(d.swallowed).isEqualTo(0)
    }

    @Test
    fun `three seconds of silence while input keeps coming is a stall`() {
        val d = StallDetector(0)
        d.onInput(100)
        d.onOutput(150)
        // A slow stream: one picture every 500 ms (too few for the wedge rule), nothing comes out.
        var t = 150L
        while (t < 3_100) {
            t += 500
            d.onInput(t)
        }
        assertThat(d.swallowed).isLessThan(StallDetector.WEDGE_FRAMES)
        assertThat(d.isStalled(3_100)).isFalse() // 2950 ms of silence
        d.onInput(3_200)
        assertThat(d.isStalled(3_200)).isTrue()
    }

    @Test
    fun `a wedge is caught early once enough pictures were swallowed`() {
        val d = StallDetector(0)
        d.onInput(100)
        d.onOutput(150)
        var t = 150L
        repeat(StallDetector.WEDGE_FRAMES - 1) {
            t += 83
            d.onInput(t)
        }
        // 17 frames in, 1.4 s of silence: one more picture is still latency, not a wedge.
        assertThat(d.swallowed).isEqualTo(StallDetector.WEDGE_FRAMES - 1)
        assertThat(d.isStalled(t)).isFalse()
        t += 83
        d.onInput(t)
        assertThat(d.swallowed).isEqualTo(StallDetector.WEDGE_FRAMES)
        assertThat(t - 150 > StallDetector.WEDGE_MILLIS).isTrue()
        assertThat(d.isStalled(t)).isTrue()
    }

    @Test
    fun `many pictures swallowed quickly is not a wedge before the minimum silence`() {
        // A fast stream (25 fps) fills the frame budget in under the time budget.
        val d = StallDetector(0)
        d.onInput(0)
        d.onOutput(10)
        var t = 10L
        repeat(StallDetector.WEDGE_FRAMES + 4) {
            t += 40
            d.onInput(t)
        }
        assertThat(t - 10 < StallDetector.WEDGE_MILLIS).isTrue()
        assertThat(d.isStalled(t)).isFalse()
        assertThat(d.isStalled(10 + StallDetector.WEDGE_MILLIS + 1)).isTrue()
    }

    @Test
    fun `a slow first picture after start is not a stall`() {
        val d = StallDetector(5_000)
        d.onInput(5_100)
        d.onInput(5_183)
        // Two pictures in, nothing out yet, 1 s later: a software decoder warming up.
        assertThat(d.isStalled(6_100)).isFalse()
        // But three seconds without a first picture is.
        d.onInput(8_050)
        assertThat(d.isStalled(8_100)).isTrue()
    }

    @Test
    fun `a decoder starved of input is not stalled, and an output clears the swallowed count`() {
        val d = StallDetector(0)
        d.onInput(100)
        repeat(30) { d.onInput(100L + it) }
        // The stream stopped 3 s ago: not the decoder's fault.
        assertThat(d.isStalled(3_200)).isFalse()
        d.onOutput(3_300)
        assertThat(d.swallowed).isEqualTo(0)
        d.reset(4_000)
        assertThat(d.hasInput).isFalse()
        assertThat(d.outputAgeMillis(4_500)).isEqualTo(500)
    }
}
