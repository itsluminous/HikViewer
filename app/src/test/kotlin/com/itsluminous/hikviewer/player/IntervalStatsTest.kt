package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntervalStatsTest {
    @Test
    fun `nothing to say before the second event`() {
        val stats = IntervalStats()
        assertThat(stats.describe()).isEqualTo("n=0")
        stats.onEvent(1_000)
        assertThat(stats.gaps).isEqualTo(0)
        assertThat(stats.describe()).isEqualTo("n=0")
    }

    @Test
    fun `even input has a small deviation, a burst a large one`() {
        val even = IntervalStats()
        (0..12).forEach { even.onEvent(it * 83L) }
        assertThat(even.gaps).isEqualTo(12)
        assertThat(even.meanMillis).isWithin(0.01).of(83.0)
        assertThat(even.stddevMillis).isWithin(0.01).of(0.0)
        assertThat(even.maxMillis).isEqualTo(83)
        assertThat(even.describe()).isEqualTo("n=12 gap=83±0 max=83")

        val burst = IntervalStats()
        // A GOP of twelve pictures in 55 ms, then nothing for 945 ms, twice.
        var t = 0L
        repeat(2) {
            repeat(12) {
                burst.onEvent(t)
                t += 5
            }
            t += 945
        }
        // 23 gaps: 22 of 5 ms and one of 950 ms.
        assertThat(burst.gaps).isEqualTo(23)
        assertThat(burst.meanMillis).isWithin(0.1).of((22 * 5 + 950) / 23.0)
        assertThat(burst.stddevMillis).isGreaterThan(150.0)
        assertThat(burst.maxMillis).isEqualTo(950)
    }

    @Test
    fun `reset keeps the last event so the next window starts with a real gap`() {
        val stats = IntervalStats()
        stats.onEvent(0)
        stats.onEvent(100)
        stats.reset()
        assertThat(stats.gaps).isEqualTo(0)
        stats.onEvent(300)
        assertThat(stats.gaps).isEqualTo(1)
        assertThat(stats.maxMillis).isEqualTo(200)
    }

    @Test
    fun `clear forgets the last event too`() {
        val stats = IntervalStats()
        stats.onEvent(0)
        stats.clear()
        stats.onEvent(5_000)
        assertThat(stats.gaps).isEqualTo(0)
    }

    @Test
    fun `a clock that goes backwards counts as a zero gap`() {
        val stats = IntervalStats()
        stats.onEvent(100)
        stats.onEvent(50)
        assertThat(stats.maxMillis).isEqualTo(0)
        assertThat(stats.gaps).isEqualTo(1)
    }
}
