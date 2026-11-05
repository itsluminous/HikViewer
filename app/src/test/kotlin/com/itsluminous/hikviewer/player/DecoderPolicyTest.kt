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
    fun `no hero means display order`() {
        val modes = DecoderPolicy.assign(tileCount = 5, heroIndex = null, liveLimit = 3)
        assertThat(modes).containsExactly(SW_LIVE, SW_LIVE, SW_LIVE, KEYFRAME, KEYFRAME).inOrder()
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
