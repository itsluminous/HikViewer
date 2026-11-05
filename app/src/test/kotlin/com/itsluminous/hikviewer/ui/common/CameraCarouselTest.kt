package com.itsluminous.hikviewer.ui.common

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.devices.Camera
import org.junit.Test

class CameraCarouselTest {
    private val cameras = (1..3).map { Camera("S", it, "cam $it", hasSignal = true, isOnline = true) }

    @Test
    fun `steps wrap around in both directions`() {
        val c = CameraCarousel(cameras, startIndex = 2)
        assertThat(c.camera!!.channelNo).isEqualTo(3)
        assertThat(c.step(+1)).isTrue()
        assertThat(c.index.value).isEqualTo(0)
        assertThat(c.step(-1)).isTrue()
        assertThat(c.index.value).isEqualTo(2)
        assertThat(c.step(-5)).isTrue()
        assertThat(c.index.value).isEqualTo(0)
    }

    @Test
    fun `an out-of-range start is clamped and an empty list never steps`() {
        assertThat(CameraCarousel(cameras, startIndex = 9).index.value).isEqualTo(2)
        assertThat(CameraCarousel(cameras, startIndex = -1).index.value).isEqualTo(0)
        val empty = CameraCarousel(emptyList(), startIndex = 0)
        assertThat(empty.camera).isNull()
        assertThat(empty.step(1)).isFalse()
        assertThat(empty.index.value).isEqualTo(0)
    }
}
