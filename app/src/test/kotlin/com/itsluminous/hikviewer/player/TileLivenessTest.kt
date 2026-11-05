package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TileLivenessTest {
    @Test
    fun `a recent picture is live on a live decoder and at one fps in keyframe mode`() {
        assertThat(TileLiveness.of(TileMode.SW_LIVE, 0)).isEqualTo(TileLiveness.LIVE)
        assertThat(TileLiveness.of(TileMode.HW_LIVE, TileLiveness.FLOWING_MILLIS - 1)).isEqualTo(TileLiveness.LIVE)
        assertThat(TileLiveness.of(TileMode.KEYFRAME, 1_500)).isEqualTo(TileLiveness.AT_ONE_FPS)
    }

    @Test
    fun `no recent picture is not live whatever the mode`() {
        assertThat(TileLiveness.of(TileMode.SW_LIVE, TileLiveness.FLOWING_MILLIS)).isEqualTo(TileLiveness.NOT_LIVE)
        assertThat(TileLiveness.of(TileMode.KEYFRAME, Long.MAX_VALUE)).isEqualTo(TileLiveness.NOT_LIVE)
        assertThat(TileLiveness.of(TileMode.HW_LIVE, Long.MAX_VALUE)).isEqualTo(TileLiveness.NOT_LIVE)
    }

    @Test
    fun `the dot is green for both picture-showing states`() {
        assertThat(TileLiveness.LIVE.isFlowing).isTrue()
        assertThat(TileLiveness.AT_ONE_FPS.isFlowing).isTrue()
        assertThat(TileLiveness.NOT_LIVE.isFlowing).isFalse()
    }

    @Test
    fun `counts tally every state`() {
        val counts =
            TileLiveness.count(
                listOf(
                    TileLiveness.LIVE,
                    TileLiveness.LIVE,
                    TileLiveness.AT_ONE_FPS,
                    TileLiveness.NOT_LIVE,
                ),
            )
        assertThat(counts).isEqualTo(LivenessCounts(cameras = 4, live = 2, atOneFps = 1))
        assertThat(counts.notLive).isEqualTo(1)
        assertThat(counts.allLive).isFalse()
        assertThat(TileLiveness.count(listOf(TileLiveness.LIVE, TileLiveness.LIVE)).allLive).isTrue()
        assertThat(TileLiveness.count(emptyList()).allLive).isFalse()
    }
}
