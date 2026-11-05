package com.itsluminous.hikviewer.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer

class YuvToRgbTest {
    /** A 4x2 picture: left half white (Y=235), right half black (Y=16), neutral chroma, padded rows. */
    private fun planes(rowPadding: Int): Triple<YuvPlane, YuvPlane, YuvPlane> {
        val width = 4
        val height = 2
        val yStride = width + rowPadding
        val y = ByteArray(yStride * height)
        for (row in 0 until height) {
            for (col in 0 until width) y[row * yStride + col] = (if (col < 2) 235 else 16).toByte()
        }
        val cStride = width / 2 + rowPadding
        val u = ByteArray(cStride) { 128.toByte() }
        val v = ByteArray(cStride) { 128.toByte() }
        return Triple(
            YuvPlane(ByteBuffer.wrap(y), yStride, 1),
            YuvPlane(ByteBuffer.wrap(u), cStride, 1),
            YuvPlane(ByteBuffer.wrap(v), cStride, 1),
        )
    }

    @Test
    fun `limited-range white and black convert to opaque white and black, honouring row strides`() {
        val (y, u, v) = planes(rowPadding = 3)
        val out = IntArray(4 * 2)
        YuvToRgb.convert(y, u, v, 4, 2, 4, 2, out)
        assertThat(out.take(2)).containsExactly(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        assertThat(out.slice(2..3)).containsExactly(0xFF000000.toInt(), 0xFF000000.toInt())
        assertThat(out.slice(4..7)).isEqualTo(out.take(4))
    }

    @Test
    fun `downscaling samples the nearest source pixel`() {
        val (y, u, v) = planes(rowPadding = 0)
        val out = IntArray(2 * 1)
        YuvToRgb.convert(y, u, v, 4, 2, 2, 1, out)
        assertThat(out[0]).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(out[1]).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun `interleaved chroma with a pixel stride of two is read correctly`() {
        // Y=128 everywhere; chroma U=240 (strong blue) interleaved with V=128 at pixel stride 2.
        val y = YuvPlane(ByteBuffer.wrap(ByteArray(2 * 2) { 128.toByte() }), 2, 1)
        val uv =
            ByteArray(2).also {
                it[0] = 240.toByte()
                it[1] = 128.toByte()
            }
        val u = YuvPlane(ByteBuffer.wrap(uv), 2, 2)
        val vBuffer = ByteBuffer.wrap(uv).also { it.position(1) }.slice()
        val v = YuvPlane(vBuffer, 2, 2)
        val out = IntArray(1)
        YuvToRgb.convert(y, u, v, 2, 2, 1, 1, out)
        val r = (out[0] shr 16) and 0xFF
        val g = (out[0] shr 8) and 0xFF
        val b = out[0] and 0xFF
        assertThat(b).isGreaterThan(200)
        assertThat(r).isLessThan(150)
        assertThat(g).isLessThan(120)
    }

    @Test
    fun `a pixel buffer that is too small is rejected`() {
        val (y, u, v) = planes(rowPadding = 0)
        val failure = runCatching { YuvToRgb.convert(y, u, v, 4, 2, 4, 2, IntArray(3)) }
        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
