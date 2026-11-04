package com.itsluminous.hikviewer.core.stream

/** Video codecs the cloud delivers. The reference DVR sends H.264 on the sub stream and HEVC on main. */
public enum class VideoCodec(
    /** NAL header length in bytes. */
    public val nalHeaderLength: Int,
    /** `MediaCodec` MIME type. */
    public val mimeType: String,
) {
    /** H.264 / AVC: 1-byte NAL header, type in the low 5 bits. */
    H264(1, "video/avc"),

    /** H.265 / HEVC: 2-byte NAL header, type in bits 1..6 of the first byte. */
    H265(2, "video/hevc"),
    ;

    /** NAL unit type of a NAL starting at [header0]. */
    public fun nalType(header0: Byte): Int {
        val b = header0.toInt() and BYTE_MASK
        return if (this == H264) b and H264_TYPE_MASK else (b ushr 1) and HEVC_TYPE_MASK
    }

    /** True for slice data (the NAL types that carry picture content). */
    public fun isVcl(type: Int): Boolean = if (this == H264) type in H264_VCL else type <= HEVC_MAX_VCL

    /** True for a random access point (IDR for H.264; BLA/IDR/CRA for HEVC). */
    public fun isIrap(type: Int): Boolean = if (this == H264) type == H264_IDR else type in HEVC_IRAP

    /** True for VPS/SPS/PPS. */
    public fun isParameterSet(type: Int): Boolean = if (this ==
        H264
    ) {
        type in H264_PARAM_SETS
    } else {
        type in HEVC_PARAM_SETS
    }

    /** True for the sequence parameter set. */
    public fun isSps(type: Int): Boolean = type == if (this == H264) H264_SPS else HEVC_SPS

    /** True for the picture parameter set. */
    public fun isPps(type: Int): Boolean = type == if (this == H264) H264_PPS else HEVC_PPS

    /** True for the HEVC video parameter set (never for H.264). */
    public fun isVps(type: Int): Boolean = this == H265 && type == HEVC_VPS

    /** True for a P/B slice (not an IRAP), the kind the inter-slice encryption sampler can use. */
    public fun isInterSlice(type: Int): Boolean = if (this == H264) type in H264_INTER else type <= HEVC_MAX_INTER

    /**
     * True for a non-VCL NAL that opens a new access unit when it follows slice data (AUD,
     * parameter sets, prefix SEI). Those belong to the *next* picture.
     */
    public fun startsAccessUnit(type: Int): Boolean =
        if (this == H264) type in H264_AU_STARTERS || type in H264_AU_STARTERS_EXT else type in HEVC_AU_STARTERS

    /** Cloud cameras encrypt only these NAL types in the selective ("header clear") variant. */
    public fun isEncryptedType(type: Int): Boolean = if (this ==
        H264
    ) {
        type in H264_ENCRYPTED
    } else {
        type in HEVC_ENCRYPTED
    }

    /** Constants describing both bitstreams. */
    public companion object {
        private const val BYTE_MASK = 0xFF
        private const val H264_TYPE_MASK = 0x1F
        private const val HEVC_TYPE_MASK = 0x3F
        private val H264_VCL = 1..5
        private const val H264_IDR = 5
        private const val H264_SPS = 7
        private const val H264_PPS = 8
        private val H264_PARAM_SETS = setOf(H264_SPS, H264_PPS)
        private val H264_INTER = setOf(1, 2)
        private val H264_AU_STARTERS = setOf(6, 7, 8, 9)
        private val H264_AU_STARTERS_EXT = 14..18
        private val H264_ENCRYPTED = setOf(5, 7, 8)
        private const val HEVC_MAX_VCL = 31
        private const val HEVC_MAX_INTER = 9
        private val HEVC_IRAP = 16..21
        private const val HEVC_VPS = 32
        private const val HEVC_SPS = 33
        private const val HEVC_PPS = 34
        private val HEVC_PARAM_SETS = setOf(HEVC_VPS, HEVC_SPS, HEVC_PPS)
        private val HEVC_AU_STARTERS = setOf(32, 33, 34, 35, 39, 41, 42, 43, 44)
        private val HEVC_ENCRYPTED = setOf(16, 17, 18, 19, 20, 21, 32, 33, 34)

        /** H.264 `profile_idc` values a real SPS can start with; used to recognise a decrypted SPS. */
        public val H264_PROFILES: Set<Int> = setOf(66, 77, 88, 100, 110, 122, 244, 44, 83, 86, 118, 128)

        /** Third byte of every HEVC VPS (`vps_reserved_0xffff_16bits` high byte is 0xFF; byte 2 is 0x0C). */
        public const val HEVC_VPS_MARKER: Int = 0x0C
    }
}

/**
 * One NAL unit without start code: header byte(s) followed by the payload, plus the presentation
 * timestamp in 90 kHz units when the transport carried one.
 */
public class NalUnit(
    /** Header + payload, no start code. */
    public val data: ByteArray,
    /** 90 kHz presentation timestamp from RTP or PES, or null when unknown. */
    public val timestamp90k: Long?,
) {
    /** Size in bytes. */
    public val size: Int get() = data.size

    /** Copy with [newData] and the same timestamp. */
    public fun withData(newData: ByteArray): NalUnit = NalUnit(newData, timestamp90k)

    override fun toString(): String = "NalUnit(size=$size, ts=$timestamp90k)"
}
