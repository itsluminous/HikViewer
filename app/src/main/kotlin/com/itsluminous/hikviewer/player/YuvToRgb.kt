package com.itsluminous.hikviewer.player

import java.nio.ByteBuffer

/**
 * One plane of a decoded YUV 4:2:0 picture as `MediaCodec` hands it out (`Image.Plane`): a buffer
 * plus the row and pixel strides, which differ between decoders (padded rows, interleaved chroma).
 */
class YuvPlane(
    /** The plane's bytes; read from absolute positions, never consumed. */
    val buffer: ByteBuffer,
    /** Bytes between the starts of two rows. */
    val rowStride: Int,
    /** Bytes between two neighbouring samples in a row (2 for interleaved chroma). */
    val pixelStride: Int,
)

/**
 * Converts a YUV 4:2:0 picture to ARGB while scaling it down to the size a tile needs, in one
 * pass with nearest-neighbour sampling: a 960×480 keyframe becomes a 320×180 thumbnail in ~60 000
 * loop iterations of integer maths, cheap enough for sixteen tiles a second on a 1 GB TV. BT.601
 * limited range, which is what the recorder's H.264 carries. Pure, so it is tested on the JVM.
 */
object YuvToRgb {
    /**
     * Writes [outWidth] × [outHeight] ARGB pixels into [out] from the [srcWidth] × [srcHeight]
     * picture in [y], [u] and [v] (the chroma planes are half size in both directions).
     */
    @Suppress("LongParameterList") // the geometry of a picture: six numbers and three planes, used once
    fun convert(
        y: YuvPlane,
        u: YuvPlane,
        v: YuvPlane,
        srcWidth: Int,
        srcHeight: Int,
        outWidth: Int,
        outHeight: Int,
        out: IntArray,
    ) {
        require(out.size >= outWidth * outHeight) { "pixel buffer too small" }
        val yb = y.buffer
        val ub = u.buffer
        val vb = v.buffer
        var o = 0
        for (row in 0 until outHeight) {
            val sy = row * srcHeight / outHeight
            val yRow = sy * y.rowStride
            val uRow = (sy shr 1) * u.rowStride
            val vRow = (sy shr 1) * v.rowStride
            for (col in 0 until outWidth) {
                val sx = col * srcWidth / outWidth
                val yy = (yb.get(yRow + sx * y.pixelStride).toInt() and BYTE_MASK) - Y_OFFSET
                val cb = (ub.get(uRow + (sx shr 1) * u.pixelStride).toInt() and BYTE_MASK) - CHROMA_OFFSET
                val cr = (vb.get(vRow + (sx shr 1) * v.pixelStride).toInt() and BYTE_MASK) - CHROMA_OFFSET
                val yScaled = Y_SCALE * yy + ROUNDING
                val r = clamp((yScaled + CR_TO_R * cr) shr FIXED_POINT_SHIFT)
                val g = clamp((yScaled - CB_TO_G * cb - CR_TO_G * cr) shr FIXED_POINT_SHIFT)
                val b = clamp((yScaled + CB_TO_B * cb) shr FIXED_POINT_SHIFT)
                out[o++] = OPAQUE or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
            }
        }
    }

    private fun clamp(value: Int): Int = when {
        value < 0 -> 0
        value > BYTE_MASK -> BYTE_MASK
        else -> value
    }

    // BT.601 limited-range coefficients in 10-bit fixed point (1.164, 1.596, 0.391, 0.813, 2.018).
    private const val FIXED_POINT_SHIFT = 10
    private const val ROUNDING = 1 shl (FIXED_POINT_SHIFT - 1)
    private const val Y_SCALE = 1192
    private const val CR_TO_R = 1634
    private const val CB_TO_G = 400
    private const val CR_TO_G = 832
    private const val CB_TO_B = 2066
    private const val Y_OFFSET = 16
    private const val CHROMA_OFFSET = 128
    private const val BYTE_MASK = 0xFF
    private const val OPAQUE = 0xFF shl 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
