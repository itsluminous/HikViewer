package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PresentationRateTest {
    @Test
    fun `at or below the threshold every tile presents every picture`() {
        for (n in 1..PresentationRate.REDUCED_RATE_TILE_THRESHOLD) {
            assertThat(PresentationRate.rule(n, isHero = false)).isEqualTo(PresentationRate.FULL_RATE)
            assertThat(PresentationRate.rule(n, isHero = true)).isEqualTo(PresentationRate.FULL_RATE)
        }
    }

    @Test
    fun `above the threshold grid tiles present every second picture and the hero every one`() {
        val n = PresentationRate.REDUCED_RATE_TILE_THRESHOLD + 1
        assertThat(PresentationRate.rule(n, isHero = false)).isEqualTo(PresentationRate.REDUCED_DIVISOR)
        assertThat(PresentationRate.rule(n, isHero = true)).isEqualTo(PresentationRate.FULL_RATE)
        assertThat(PresentationRate.rule(16, isHero = false)).isEqualTo(2)
    }

    @Test
    fun `the switch is off, so the divisor every tile gets is the full rate`() {
        assertThat(PresentationRate.ENABLED).isFalse()
        assertThat(PresentationRate.divisor(16, isHero = false)).isEqualTo(PresentationRate.FULL_RATE)
        assertThat(PresentationRate.divisor(16, isHero = true)).isEqualTo(PresentationRate.FULL_RATE)
    }
}
