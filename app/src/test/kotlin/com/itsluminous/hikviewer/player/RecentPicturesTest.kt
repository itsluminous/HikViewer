package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecentPicturesTest {
    @Test
    fun `empty rings describe as empty`() {
        assertThat(RecentPictures(4).describe()).isEqualTo("in[] out[]")
    }

    @Test
    fun `entries are listed oldest first in milliseconds with a K on keyframes`() {
        val r = RecentPictures(4)
        r.recordIn(1_000_000, 26_000, keyframe = true)
        r.recordIn(1_083_000, 3_500, keyframe = false)
        r.recordOut(1_000_000)
        assertThat(r.describe()).isEqualTo("in[1000/26000K 1083/3500] out[1000]")
    }

    @Test
    fun `only the last capacity entries are kept, in arrival order, across many wraps`() {
        val r = RecentPictures(3)
        repeat(10) { r.recordIn(it * 1_000L, it, keyframe = false) }
        repeat(7) { r.recordOut(it * 1_000L) }
        assertThat(r.describe()).isEqualTo("in[7/7 8/8 9/9] out[4 5 6]")
    }
}
