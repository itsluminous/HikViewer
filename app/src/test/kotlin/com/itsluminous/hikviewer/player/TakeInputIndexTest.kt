package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.ArrayDeque

class TakeInputIndexTest {
    private fun free(vararg indexes: Int) = ArrayDeque<Int>().apply { indexes.forEach(::addLast) }

    @Test
    fun `a free buffer stays free while nothing waits for it`() {
        val inputs = free(3, 4)

        assertThat(takeInputIndex(inputs, csdLeft = 0, pendingPictures = 0)).isNull()
        assertThat(inputs).containsExactly(3, 4).inOrder()
    }

    @Test
    fun `buffers offered before the first picture are all used once pictures arrive`() {
        // The codec offers its buffers right after start(), before the stream has delivered
        // anything; the regression took and dropped every one of them.
        val inputs = free(0, 1, 2, 3)
        repeat(4) { assertThat(takeInputIndex(inputs, csdLeft = 0, pendingPictures = 0)).isNull() }

        assertThat((1..4).map { takeInputIndex(inputs, csdLeft = 0, pendingPictures = 5) })
            .containsExactly(0, 1, 2, 3)
            .inOrder()
    }

    @Test
    fun `parameter sets to resend take a buffer even with no picture waiting`() {
        val inputs = free(7)

        assertThat(takeInputIndex(inputs, csdLeft = 2, pendingPictures = 0)).isEqualTo(7)
        assertThat(inputs).isEmpty()
    }

    @Test
    fun `no free buffer gives nothing`() {
        assertThat(takeInputIndex(free(), csdLeft = 1, pendingPictures = 3)).isNull()
    }
}
