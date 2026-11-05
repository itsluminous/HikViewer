package com.itsluminous.hikviewer.ui.playback

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.stream.AccessUnit
import com.itsluminous.hikviewer.core.stream.VideoCodec
import org.junit.Test
import java.time.LocalDateTime

class KeyframeRingTest {
    private val t0 = LocalDateTime.of(2026, 11, 4, 6, 0, 0)
    private val unit = AccessUnit(VideoCodec.H265, ByteArray(4), 0L, isKeyframe = true, config = null)

    private fun ring(vararg seconds: Long) =
        KeyframeRing(64).apply { seconds.forEach { add(t0.plusSeconds(it), unit) } }

    @Test
    fun `serves the keyframe at or within the tolerance after the step, else the nearest shortly before it`() {
        assertThat(ring(0, 1, 2, 10, 11).serve(t0.plusSeconds(10))?.time).isEqualTo(t0.plusSeconds(10))
        assertThat(ring(0, 1, 2, 11, 12).serve(t0.plusSeconds(10))?.time).isEqualTo(t0.plusSeconds(11)) // after wins
        assertThat(ring(0, 1, 2, 8, 9).serve(t0.plusSeconds(10))?.time).isEqualTo(t0.plusSeconds(9)) // nearest before
        assertThat(ring(0, 7, 13).serve(t0.plusSeconds(10))).isNull() // beyond the tolerance either way
        val r = ring(0, 9, 12)
        assertThat(r.has(t0.plusSeconds(10))).isTrue() // 12 is within the tolerance after
        assertThat(r.has(t0.plusSeconds(13))).isFalse() // 12 is before: coverage is strict
        assertThat(r.has(t0.plusSeconds(30))).isFalse()
    }

    @Test
    fun `the entry served last is skipped, so a turn never shows the same picture twice in a row`() {
        val r = ring(9, 10)
        assertThat(r.serve(t0.plusSeconds(10))?.time).isEqualTo(t0.plusSeconds(10))
        assertThat(r.serve(t0.plusSeconds(10))?.time).isEqualTo(t0.plusSeconds(9)) // 10 was just shown; 9 is in range
        assertThat(r.serve(t0.plusSeconds(10))?.time).isEqualTo(t0.plusSeconds(10)) // and back
        assertThat(r.has(t0.plusSeconds(10))).isTrue() // "has" ignores what was shown
    }

    @Test
    fun `holds at most its capacity, the oldest fetched going first, and a repeated second replaces`() {
        val r = KeyframeRing(3)
        r.add(t0, unit)
        r.add(t0.plusSeconds(1), unit)
        r.add(t0.plusSeconds(2), unit)
        r.add(t0.plusSeconds(3), unit)
        assertThat(r.size).isEqualTo(3)
        assertThat(r.has(t0.minusSeconds(3))).isFalse()
        assertThat(r.has(t0.plusSeconds(3))).isTrue()
        r.add(t0.plusSeconds(3), unit)
        assertThat(r.size).isEqualTo(3)
        r.clear()
        assertThat(r.size).isEqualTo(0)
        assertThat(r.serve(t0.plusSeconds(3))).isNull()
    }
}
