package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveBudgetTest {
    private fun caps(
        lowRam: Boolean = false,
        mb: Long = 1_800,
        cores: Int = 4,
    ) = DeviceCapabilities(lowRam, mb shl 20, cores)

    @Test
    fun `static ceiling follows RAM, low-ram flag and cores`() {
        assertThat(LiveBudget.staticCeiling(caps(mb = 1_024))).isEqualTo(4)
        assertThat(LiveBudget.staticCeiling(caps(lowRam = true, mb = 4_000))).isEqualTo(4)
        assertThat(LiveBudget.staticCeiling(caps(mb = 1_800))).isEqualTo(8)
        assertThat(LiveBudget.staticCeiling(caps(mb = 4_000))).isEqualTo(16)
        assertThat(LiveBudget.staticCeiling(caps(mb = 4_000, cores = 2))).isEqualTo(4)
    }

    @Test
    fun `demotes after two seconds of lag, not before`() {
        val log = mutableListOf<String>()
        val budget = LiveBudget(caps(), log::add)
        assertThat(budget.liveLimit).isEqualTo(8)
        assertThat(budget.sample(0, 10)).isEqualTo(8)
        assertThat(budget.sample(1_900, 10)).isEqualTo(8)
        assertThat(budget.sample(2_000, 10)).isEqualTo(7)
        // The lag clock restarts after a demotion so tiles are shed one at a time.
        assertThat(budget.sample(2_500, 10)).isEqualTo(7)
        assertThat(budget.sample(4_000, 10)).isEqualTo(6)
        assertThat(log.filter { "demote" in it }).hasSize(2)
        assertThat(log.first()).contains("ceiling=8")
    }

    @Test
    fun `a calm sample resets the lag clock`() {
        val budget = LiveBudget(caps())
        budget.sample(0, 10)
        budget.sample(1_500, 0)
        assertThat(budget.sample(2_500, 10)).isEqualTo(8)
        assertThat(budget.sample(4_500, 10)).isEqualTo(7)
    }

    @Test
    fun `promotes back after headroom but never above the ceiling`() {
        val budget = LiveBudget(caps())
        budget.sample(0, 10)
        budget.sample(2_000, 10)
        assertThat(budget.liveLimit).isEqualTo(7)
        budget.sample(3_000, 0)
        assertThat(budget.sample(3_000 + LiveBudget.HEADROOM_PROMOTE_MILLIS - 1, 0)).isEqualTo(7)
        assertThat(budget.sample(3_000 + LiveBudget.HEADROOM_PROMOTE_MILLIS, 0)).isEqualTo(8)
        assertThat(budget.sample(100_000, 0)).isEqualTo(8)
    }

    @Test
    fun `never demotes below one live tile`() {
        val budget = LiveBudget(caps(mb = 512, cores = 1))
        assertThat(budget.liveLimit).isEqualTo(4)
        var t = 0L
        repeat(10) {
            t += LiveBudget.LAG_DEMOTE_MILLIS
            budget.sample(t, 99)
        }
        assertThat(budget.liveLimit).isEqualTo(LiveBudget.MIN_LIVE)
    }

    @Test
    fun `lag threshold is exclusive`() {
        val budget = LiveBudget(caps())
        budget.sample(0, LiveBudget.LAG_FRAMES)
        assertThat(budget.sample(5_000, LiveBudget.LAG_FRAMES)).isEqualTo(8)
    }
}
