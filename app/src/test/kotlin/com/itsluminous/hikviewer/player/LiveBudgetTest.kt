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
        assertThat(budget.sample(12_000, 10)).isEqualTo(6)
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
            t += LiveBudget.MIN_CHANGE_INTERVAL_MILLIS
            budget.sample(t, 99)
        }
        assertThat(budget.liveLimit).isEqualTo(LiveBudget.MIN_LIVE)
    }

    @Test
    fun `changes in either direction are at least ten seconds apart`() {
        val budget = LiveBudget(caps())
        // Persistent lag: the second and third demotions wait for the hysteresis window, not 2 s.
        budget.sample(0, 30)
        assertThat(budget.sample(2_000, 30)).isEqualTo(7)
        assertThat(budget.sample(4_000, 30)).isEqualTo(7)
        assertThat(budget.sample(11_999, 30)).isEqualTo(7)
        assertThat(budget.sample(12_000, 30)).isEqualTo(6)
        // Headroom right after a demotion: the promotion also honours the window.
        budget.sample(12_500, 0)
        assertThat(budget.sample(12_500 + LiveBudget.HEADROOM_PROMOTE_MILLIS, 0)).isEqualTo(7)
        // Lag again right after the promotion: no immediate flip back.
        val promotedAt = 12_500 + LiveBudget.HEADROOM_PROMOTE_MILLIS
        budget.sample(promotedAt + 100, 30)
        assertThat(budget.sample(promotedAt + LiveBudget.LAG_DEMOTE_MILLIS + 100, 30)).isEqualTo(7)
        assertThat(budget.sample(promotedAt + LiveBudget.MIN_CHANGE_INTERVAL_MILLIS, 30)).isEqualTo(6)
    }

    @Test
    fun `a promotion that relapses doubles the calm the next one needs, a lasting one resets it`() {
        val budget = LiveBudget(caps())
        var t = 0L
        budget.sample(t, 30)
        t += LiveBudget.LAG_DEMOTE_MILLIS
        assertThat(budget.sample(t, 30)).isEqualTo(7)
        // Calm → promote after the default 15 s.
        t += 1_000
        budget.sample(t, 0)
        t += LiveBudget.HEADROOM_PROMOTE_MILLIS
        assertThat(budget.sample(t, 0)).isEqualTo(8)
        // Lags again within the relapse window → demoted, and the calm requirement doubles.
        t += LiveBudget.MIN_CHANGE_INTERVAL_MILLIS
        budget.sample(t, 30)
        t += LiveBudget.LAG_DEMOTE_MILLIS
        assertThat(budget.sample(t, 30)).isEqualTo(7)
        assertThat(budget.headroomMillis).isEqualTo(LiveBudget.HEADROOM_PROMOTE_MILLIS * 2)
        t += 1_000
        budget.sample(t, 0)
        t += LiveBudget.HEADROOM_PROMOTE_MILLIS
        assertThat(budget.sample(t, 0)).isEqualTo(7)
        t += LiveBudget.HEADROOM_PROMOTE_MILLIS
        assertThat(budget.sample(t, 0)).isEqualTo(8)
        // This promotion holds past the relapse window: the requirement goes back to the default.
        t += LiveBudget.RELAPSE_MILLIS
        budget.sample(t, 0)
        assertThat(budget.headroomMillis).isEqualTo(LiveBudget.HEADROOM_PROMOTE_MILLIS)
    }

    @Test
    fun `relapse back-off is capped`() {
        val budget = LiveBudget(caps())
        var t = 0L
        repeat(10) {
            budget.sample(t, 30)
            t += LiveBudget.MIN_CHANGE_INTERVAL_MILLIS
            budget.sample(t, 30)
            t += 1_000
            budget.sample(t, 0)
            t += budget.headroomMillis
            budget.sample(t, 0)
            t += LiveBudget.MIN_CHANGE_INTERVAL_MILLIS
        }
        assertThat(budget.headroomMillis).isAtMost(LiveBudget.MAX_HEADROOM_MILLIS)
        assertThat(budget.headroomMillis).isGreaterThan(LiveBudget.HEADROOM_PROMOTE_MILLIS)
    }

    @Test
    fun `lag threshold is exclusive`() {
        val budget = LiveBudget(caps())
        budget.sample(0, LiveBudget.LAG_FRAMES)
        assertThat(budget.sample(5_000, LiveBudget.LAG_FRAMES)).isEqualTo(8)
    }
}

class LiveBudgetProbingTest {
    private val caps = DeviceCapabilities(false, 1_800L shl 20, 4)
    private val calm = LiveBudget.HEADROOM_PROMOTE_MILLIS
    private val mb = 1024L * 1024L

    @Test
    fun `without headroom readings the budget never leaves the ceiling`() {
        val budget = LiveBudget(caps)
        assertThat(budget.maxLive).isEqualTo(12)
        budget.sample(0, 0)
        assertThat(budget.sample(calm, 0)).isEqualTo(8)
        assertThat(budget.sample(10 * calm, 0)).isEqualTo(8)
    }

    @Test
    fun `the NONE probe reads as no headroom, and a limit above the ceiling without readings is shed`() {
        val none = HeadroomProbe.NONE
        assertThat(none.cpuIdleFraction).isEqualTo(0f)
        assertThat(none.spareMemoryBytes).isEqualTo(0L)
        val log = mutableListOf<String>()
        val budget = LiveBudget(caps, log::add)
        budget.sample(0, 0, 1f, 500 * mb)
        assertThat(budget.sample(calm, 0, 1f, 500 * mb)).isEqualTo(9)
        var now = calm + LiveBudget.MIN_CHANGE_INTERVAL_MILLIS
        budget.sample(now, 0, none.cpuIdleFraction, none.spareMemoryBytes)
        now += LiveBudget.LAG_DEMOTE_MILLIS
        assertThat(budget.sample(now, 0, none.cpuIdleFraction, none.spareMemoryBytes)).isEqualTo(8)
        assertThat(log.last()).contains("demote, idle=0 %, spare=0 MB")
        assertThat(budget.sample(now + 10 * calm, 0, none.cpuIdleFraction, none.spareMemoryBytes)).isEqualTo(8)
    }

    @Test
    fun `never probes past the tiles there are`() {
        val budget = LiveBudget(caps)
        var now = 0L
        budget.sample(now, 0, 1f, 500 * mb, tileCount = 9)
        now += calm
        assertThat(budget.sample(now, 0, 1f, 500 * mb, tileCount = 9)).isEqualTo(9)
        now += 10 * calm
        assertThat(budget.sample(now, 0, 1f, 500 * mb, tileCount = 9)).isEqualTo(9)
        // A bigger grid may go on from there.
        now += calm
        assertThat(budget.sample(now, 0, 1f, 500 * mb, tileCount = 12)).isEqualTo(10)
    }

    @Test
    fun `probes one tile at a time with a free core and spare memory, up to the extra tiles`() {
        val log = mutableListOf<String>()
        val budget = LiveBudget(caps, log::add)
        var now = 0L
        budget.sample(now, 0, idleFraction = 1f, spareMemoryBytes = 500 * mb)
        now += calm - 1
        assertThat(budget.sample(now, 0, 1f, 500 * mb)).isEqualTo(8)
        now += 1
        assertThat(budget.sample(now, 0, 1f, 500 * mb)).isEqualTo(9)
        assertThat(log.last()).contains("above the ceiling (idle=100 %, spare=500 MB)")
        // Each further probe waits for the calm period again.
        now += calm - 1
        assertThat(budget.sample(now, 0, 1f, 500 * mb)).isEqualTo(9)
        now += 1
        assertThat(budget.sample(now, 0, 1f, 500 * mb)).isEqualTo(10)
        now += calm
        assertThat(budget.sample(now, 0, 1f, 500 * mb)).isEqualTo(11)
        now += calm
        assertThat(budget.sample(now, 0, 1f, 500 * mb)).isEqualTo(12)
        now += 10 * calm
        assertThat(budget.sample(now, 0, 1f, 500 * mb)).isEqualTo(12)
    }

    @Test
    fun `a busy core or little memory withholds the probe but not a promotion below the ceiling`() {
        val budget = LiveBudget(caps)
        budget.sample(0, 10)
        budget.sample(LiveBudget.LAG_DEMOTE_MILLIS, 10)
        assertThat(budget.liveLimit).isEqualTo(7)
        var now = 20_000L
        budget.sample(now, 0, idleFraction = 0f, spareMemoryBytes = 0L)
        now += calm
        // Back to the ceiling needs no headroom reading at all.
        assertThat(budget.sample(now, 0, 0f, 0L)).isEqualTo(8)
        now += calm
        assertThat(budget.sample(now, 0, LiveBudget.IDLE_PROMOTE_FRACTION - 0.01f, 500 * mb)).isEqualTo(8)
        now += calm
        assertThat(budget.sample(now, 0, 1f, LiveBudget.EXTRA_TILE_MEMORY_BYTES - 1)).isEqualTo(8)
        now += calm
        assertThat(
            budget.sample(now, 0, LiveBudget.IDLE_PROMOTE_FRACTION, LiveBudget.EXTRA_TILE_MEMORY_BYTES),
        ).isEqualTo(9)
    }

    @Test
    fun `a probed tile is shed when the headroom goes, after the lag window, and the relapse backs off`() {
        val log = mutableListOf<String>()
        val budget = LiveBudget(caps, log::add)
        budget.sample(0, 0, 1f, 500 * mb)
        budget.sample(calm, 0, 1f, 500 * mb)
        assertThat(budget.liveLimit).isEqualTo(9)
        val busy = calm + LiveBudget.MIN_CHANGE_INTERVAL_MILLIS
        budget.sample(busy, 0, LiveBudget.IDLE_DEMOTE_FRACTION - 0.01f, 500 * mb)
        assertThat(budget.sample(busy + LiveBudget.LAG_DEMOTE_MILLIS - 1, 0, 0.05f, 500 * mb)).isEqualTo(9)
        assertThat(budget.sample(busy + LiveBudget.LAG_DEMOTE_MILLIS, 0, 0.05f, 500 * mb)).isEqualTo(8)
        assertThat(log.last()).contains("demote, idle=5 %, spare=500 MB, limit=8, next promotion after 30 s calm")
        assertThat(budget.headroomMillis).isEqualTo(2 * calm)
        // Memory alone does it too, and at the ceiling nothing but a queue can demote.
        val later = busy + 60_000
        budget.sample(later, 0, 1f, LiveBudget.LOW_MEMORY_BYTES - 1)
        assertThat(budget.sample(later + LiveBudget.LAG_DEMOTE_MILLIS, 0, 1f, 0L)).isEqualTo(8)
    }
}
