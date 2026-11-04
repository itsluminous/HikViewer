package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.player.TileMode.HW_LIVE
import com.itsluminous.hikviewer.player.TileMode.KEYFRAME
import com.itsluminous.hikviewer.player.TileMode.SW_LIVE
import org.junit.Test

class DecoderPolicyTest {
    @Test
    fun `focused and hero get hardware, the rest software up to the limit`() {
        val modes = DecoderPolicy.assign(tileCount = 6, focusedIndex = 4, heroIndex = 0, liveLimit = 4)
        assertThat(modes).containsExactly(HW_LIVE, SW_LIVE, SW_LIVE, KEYFRAME, HW_LIVE, KEYFRAME).inOrder()
    }

    @Test
    fun `focused tile is live even when it is the last one`() {
        val modes = DecoderPolicy.assign(tileCount = 12, focusedIndex = 11, heroIndex = null, liveLimit = 1)
        assertThat(modes.count { it.isLive }).isEqualTo(1)
        assertThat(modes[11]).isEqualTo(HW_LIVE)
    }

    @Test
    fun `focused equals hero uses one hardware decoder`() {
        val modes = DecoderPolicy.assign(tileCount = 4, focusedIndex = 0, heroIndex = 0, liveLimit = 4)
        assertThat(modes).containsExactly(HW_LIVE, SW_LIVE, SW_LIVE, SW_LIVE).inOrder()
    }

    @Test
    fun `hardware decoders are capped`() {
        val modes = DecoderPolicy.assign(5, focusedIndex = 1, heroIndex = 3, liveLimit = 5, maxHardwareDecoders = 1)
        assertThat(modes[1]).isEqualTo(HW_LIVE)
        assertThat(modes[3]).isEqualTo(SW_LIVE)
    }

    @Test
    fun `no focus means display order`() {
        val modes = DecoderPolicy.assign(tileCount = 5, focusedIndex = null, heroIndex = null, liveLimit = 3)
        assertThat(modes).containsExactly(SW_LIVE, SW_LIVE, SW_LIVE, KEYFRAME, KEYFRAME).inOrder()
    }

    @Test
    fun `out of range focus and empty grids are harmless`() {
        assertThat(DecoderPolicy.assign(0, 0, 0, 8)).isEmpty()
        val modes = DecoderPolicy.assign(2, focusedIndex = 9, heroIndex = -1, liveLimit = 8)
        assertThat(modes).containsExactly(SW_LIVE, SW_LIVE).inOrder()
    }

    @Test
    fun `limit above the count makes everything live`() {
        assertThat(DecoderPolicy.assign(3, 0, null, 16).all { it.isLive }).isTrue()
    }

    @Test
    fun `software fallback on transient or error 1100`() {
        assertThat(DecoderPolicy.shouldFallBackToSoftware(isTransient = true, errorCode = 0)).isTrue()
        assertThat(DecoderPolicy.shouldFallBackToSoftware(isTransient = false, errorCode = 1100)).isTrue()
        assertThat(DecoderPolicy.shouldFallBackToSoftware(isTransient = false, errorCode = 2)).isFalse()
    }
}
