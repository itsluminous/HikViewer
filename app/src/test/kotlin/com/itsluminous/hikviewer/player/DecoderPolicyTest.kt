package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.player.TileMode.HW_LIVE
import com.itsluminous.hikviewer.player.TileMode.KEYFRAME
import com.itsluminous.hikviewer.player.TileMode.SW_LIVE
import org.junit.Test

class DecoderPolicyTest {
    @Test
    fun `hero gets hardware, the rest software in display order up to the limit`() {
        val modes = DecoderPolicy.assign(tileCount = 6, heroIndex = 0, liveLimit = 4)
        assertThat(modes).containsExactly(HW_LIVE, SW_LIVE, SW_LIVE, SW_LIVE, KEYFRAME, KEYFRAME).inOrder()
    }

    /** Settings → Live cameras = 12 on a 4 x 4 grid: the first twelve in arrangement order, the last four stills. */
    @Test
    fun `a user ceiling below the tile count makes exactly that many live, in arrangement order`() {
        val flat = DecoderPolicy.assign(tileCount = 16, heroIndex = null, liveLimit = 12)
        assertThat(flat.count { it.isLive }).isEqualTo(12)
        assertThat(flat.take(12).all { it == SW_LIVE }).isTrue()
        assertThat(flat.drop(12)).containsExactly(KEYFRAME, KEYFRAME, KEYFRAME, KEYFRAME).inOrder()
        // In a 1 + 15 layout the hero is the first of the twelve.
        val hero = DecoderPolicy.assign(tileCount = 16, heroIndex = 0, liveLimit = 12)
        assertThat(hero.first()).isEqualTo(HW_LIVE)
        assertThat(hero.count { it.isLive }).isEqualTo(12)
        assertThat(hero.drop(12).all { it == KEYFRAME }).isTrue()
    }

    /** A ceiling of 12 or 16 on a 12-tile grid: every tile is live, no still anywhere. */
    @Test
    fun `a user ceiling at or above the tile count leaves no stills`() {
        assertThat(DecoderPolicy.assign(tileCount = 12, heroIndex = null, liveLimit = 12).all { it.isLive }).isTrue()
        assertThat(DecoderPolicy.assign(tileCount = 12, heroIndex = 0, liveLimit = 16).all { it.isLive }).isTrue()
        assertThat(DecoderPolicy.assign(tileCount = 4, heroIndex = null, liveLimit = 4).all { it == SW_LIVE }).isTrue()
    }

    @Test
    fun `a hero anywhere is live first, even with a budget of one`() {
        val modes = DecoderPolicy.assign(tileCount = 12, heroIndex = 11, liveLimit = 1)
        assertThat(modes.count { it.isLive }).isEqualTo(1)
        assertThat(modes[11]).isEqualTo(HW_LIVE)
    }

    @Test
    fun `hardware decoders are capped`() {
        val modes = DecoderPolicy.assign(5, heroIndex = 3, liveLimit = 5, maxHardwareDecoders = 0)
        assertThat(modes[3]).isEqualTo(SW_LIVE)
        assertThat(modes.count { it.isLive }).isEqualTo(5)
    }

    @Test
    fun `a flat grid is software in display order and leaves the hardware slots idle`() {
        val modes = DecoderPolicy.assign(tileCount = 5, heroIndex = null, liveLimit = 3)
        assertThat(modes).containsExactly(SW_LIVE, SW_LIVE, SW_LIVE, KEYFRAME, KEYFRAME).inOrder()
        // Measured on the MiTV: a paced hardware tile is jerkier than a paced software one (see the KDoc).
        assertThat(DecoderPolicy.assign(tileCount = 12, heroIndex = null, liveLimit = 8).count { it == HW_LIVE })
            .isEqualTo(0)
    }

    @Test
    fun `out of range hero and empty grids are harmless`() {
        assertThat(DecoderPolicy.assign(0, 0, 8)).isEmpty()
        val modes = DecoderPolicy.assign(2, heroIndex = -1, liveLimit = 8)
        assertThat(modes).containsExactly(SW_LIVE, SW_LIVE).inOrder()
        assertThat(DecoderPolicy.assign(2, heroIndex = 9, liveLimit = 8)).containsExactly(SW_LIVE, SW_LIVE).inOrder()
    }

    @Test
    fun `limit above the count makes everything live`() {
        assertThat(DecoderPolicy.assign(3, 0, 16).all { it.isLive }).isTrue()
    }

    @Test
    fun `hardware only keeps the hero live and nothing else, whatever the budget`() {
        val modes = DecoderPolicy.assign(tileCount = 6, heroIndex = 2, liveLimit = 6, hardwareOnly = true)
        assertThat(modes).containsExactly(KEYFRAME, KEYFRAME, HW_LIVE, KEYFRAME, KEYFRAME, KEYFRAME).inOrder()
        val noHero = DecoderPolicy.assign(tileCount = 4, heroIndex = null, liveLimit = 4, hardwareOnly = true)
        assertThat(noHero.none { it.isLive }).isTrue()
    }

    @Test
    fun `software fallback on transient or error 1100`() {
        assertThat(DecoderPolicy.shouldFallBackToSoftware(isTransient = true, errorCode = 0)).isTrue()
        assertThat(DecoderPolicy.shouldFallBackToSoftware(isTransient = false, errorCode = 1100)).isTrue()
        assertThat(DecoderPolicy.shouldFallBackToSoftware(isTransient = false, errorCode = 2)).isFalse()
    }
}
