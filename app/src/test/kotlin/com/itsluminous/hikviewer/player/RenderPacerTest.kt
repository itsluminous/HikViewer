package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RenderPacerTest {
    private val slot = RenderPacer.DEFAULT_SLOT_NANOS

    @Test
    fun `slot zero renders now`() {
        assertThat(RenderPacer.presentAt(123_456L, 0L)).isEqualTo(123_456L)
    }

    @Test
    fun `pictures are presented at the next slot boundary, never in the past`() {
        val at = RenderPacer.presentAt(10 * slot + 1, slot)
        assertThat(at).isEqualTo(11 * slot)
        assertThat(RenderPacer.presentAt(10 * slot, slot)).isEqualTo(11 * slot) // exactly on a boundary: next one
        assertThat(RenderPacer.presentAt(11 * slot - 1, slot)).isEqualTo(11 * slot)
    }

    @Test
    fun `decoders that finish within one slot share a presentation time`() {
        // Eight tiles whose pictures come out spread over a slot land in one SurfaceFlinger frame.
        val times = (0 until 8).map { RenderPacer.presentAt(42 * slot + it * (slot / 8), slot) }
        assertThat(times.toSet()).hasSize(1)
    }

    @Test
    fun `the default slot holds the recorder's 12 fps without dropping pictures`() {
        // Twelve pictures a second, one every 1/12 s, map to twelve distinct slots of 1/15 s.
        val period = 1_000_000_000L / 12
        val slots = (0 until 12).map { RenderPacer.presentAt(it * period + 5_000_000L, slot) }
        assertThat(slots.toSet()).hasSize(12)
        assertThat(slot).isEqualTo(1_000_000_000L / 15)
    }
}
