package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CpuHeadroomProbeTest {
    private val probe = CpuHeadroomProbe(burstMillis = 10)

    @Test
    fun `reads unknown until the first burst and the minimum of the window after it`() {
        assertThat(probe.idleFraction).isEqualTo(0f)
        val gen = probe.currentGeneration
        probe.record(gen, 10) // 10 ms of CPU in 10 ms of wall: the thread had a core to itself
        assertThat(probe.idleFraction).isEqualTo(1f)
        probe.record(gen, 50) // then it waited: 20 %
        assertThat(probe.idleFraction).isWithin(0.001f).of(0.2f)
        repeat(CpuHeadroomProbe.SAMPLES) { probe.record(gen, 20) }
        // The 20 % burst has left the window of the last SAMPLES bursts.
        assertThat(probe.idleFraction).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `stop forgets every reading and a burst from before the stop is ignored`() {
        val stale = probe.currentGeneration
        probe.record(stale, 10)
        assertThat(probe.idleFraction).isEqualTo(1f)
        probe.stop()
        assertThat(probe.idleFraction).isEqualTo(0f)
        probe.record(stale, 10)
        assertThat(probe.idleFraction).isEqualTo(0f)
    }

    @Test
    fun `a burst that never finished reads as no idle at all`() {
        probe.record(probe.currentGeneration, 0)
        assertThat(probe.idleFraction).isEqualTo(1f) // zero wall time: measured nothing, counts as free
        probe.record(probe.currentGeneration, 100_000)
        assertThat(probe.idleFraction).isEqualTo(0f)
    }
}
