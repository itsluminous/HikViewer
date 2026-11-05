package com.itsluminous.hikviewer.core.stream

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ByteBuilderTest {
    @Test
    fun `reuse after reset yields exactly the new contents`() {
        val b = ByteBuilder(initialCapacity = 4)
        b.append(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)) // grows past the initial capacity
        val first = b.toByteArray()
        b.reset()
        b.append(10)
        b.append(byteArrayOf(0, 11, 12, 0), from = 1, to = 3)
        assertThat(first).isEqualTo(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
        assertThat(b.size).isEqualTo(3)
        assertThat(b.toByteArray()).isEqualTo(byteArrayOf(10, 11, 12))
        // The copy handed out earlier is independent of later writes.
        assertThat(first[0]).isEqualTo(1)
    }

    @Test
    fun `dropFirst slides the remaining bytes down`() {
        val b = ByteBuilder()
        b.append(byteArrayOf(1, 2, 3, 4, 5))
        b.dropFirst(2)
        assertThat(b.toByteArray()).isEqualTo(byteArrayOf(3, 4, 5))
        assertThat(b[0]).isEqualTo(3)
        b.dropFirst(10)
        assertThat(b.size).isEqualTo(0)
        b.dropFirst(0)
        assertThat(b.toByteArray()).isEmpty()
    }

    @Test
    fun `ranged copy and empty appends`() {
        val b = ByteBuilder()
        b.append(ByteArray(0))
        b.append(byteArrayOf(7, 8, 9), from = 2, to = 2)
        assertThat(b.size).isEqualTo(0)
        b.append(byteArrayOf(7, 8, 9))
        assertThat(b.toByteArray(1, 3)).isEqualTo(byteArrayOf(8, 9))
        assertThat(b.array().size).isAtLeast(3)
    }
}
