package com.itsluminous.hikviewer.core.stream

/** Codec-specific data `MediaCodec` needs before the first frame (`csd-0`, `csd-1`, ...). */
public class CodecConfig(
    /** The codec. */
    public val codec: VideoCodec,
    /** HEVC VPS with start code; null for H.264. */
    public val vps: ByteArray?,
    /** SPS with start code. */
    public val sps: ByteArray,
    /** PPS with start code. */
    public val pps: ByteArray,
) {
    /**
     * Buffers in `csd-N` order: H.264 → `[sps, pps]`; HEVC → one buffer with VPS+SPS+PPS, which is
     * what the Android HEVC decoders expect as `csd-0`.
     */
    public val csd: List<ByteArray> get() =
        if (codec == VideoCodec.H264) listOf(sps, pps) else listOf((vps ?: ByteArray(0)) + sps + pps)

    override fun equals(other: Any?): Boolean = other is CodecConfig &&
        codec == other.codec &&
        sps.contentEquals(other.sps) &&
        pps.contentEquals(other.pps) &&
        (vps?.contentEquals(other.vps ?: ByteArray(0)) ?: (other.vps == null))

    override fun hashCode(): Int = sps.contentHashCode() * 31 + pps.contentHashCode()
}

/** One picture's worth of Annex-B data, ready for `MediaCodec.queueInputBuffer`. */
public class AccessUnit(
    /** The codec. */
    public val codec: VideoCodec,
    /** Annex-B bytes: every NAL of the picture with 4-byte start codes. */
    public val data: ByteArray,
    /** Presentation time in microseconds (90 kHz timestamp converted), or null when the transport had none. */
    public val presentationTimeUs: Long?,
    /** True when the picture is an IRAP (IDR/CRA): a decoder can start here. */
    public val isKeyframe: Boolean,
    /** Parameter sets in effect for this picture, when known. */
    public val config: CodecConfig?,
) {
    /** Size in bytes. */
    public val size: Int get() = data.size

    override fun toString(): String = "AccessUnit($codec, $size bytes, pts=$presentationTimeUs, key=$isKeyframe)"
}

/**
 * Groups decrypted NAL units into access units (pictures).
 *
 * A new picture starts when a slice follows a slice with a different timestamp or with the
 * first-slice flag set (`first_mb_in_slice == 0` for H.264, `first_slice_segment_in_pic_flag` for
 * HEVC; both are the first bit of the slice header), and when a non-VCL NAL that belongs to the next
 * picture (AUD, parameter set, prefix SEI) follows slice data. Parameter sets are remembered so
 * every keyframe can carry a [CodecConfig] for `MediaCodec`. RTP timestamps wrap at 32 bits; they
 * are unwrapped here so presentation times increase monotonically.
 *
 * NALs are held by reference until the picture is complete and then written once into an array
 * of exactly the right size, so each picture costs one copy (the one `MediaCodec` needs anyway).
 */
public class AccessUnitAssembler(
    /** The codec; known from [NalDecryptor.codec] before the first NAL reaches the assembler. */
    public val codec: VideoCodec,
) {
    private val current = ArrayList<ByteArray>()
    private var currentBytes = 0
    private var currentTimestamp: Long? = null
    private var currentHasVcl = false
    private var currentKeyframe = false
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var timestampBase = 0L
    private var lastRawTimestamp: Long? = null

    /** Parameter sets seen so far, once SPS and PPS are both known. */
    public val config: CodecConfig? get() {
        val s = sps ?: return null
        val p = pps ?: return null
        if (codec == VideoCodec.H265 && vps == null) return null
        return CodecConfig(codec, vps, s, p)
    }

    /** Adds [nal]; returns the previous picture when [nal] starts a new one. */
    public fun feed(nal: NalUnit): AccessUnit? {
        val data = nal.data
        if (data.isEmpty()) return null
        val type = codec.nalType(data[0])
        val vcl = codec.isVcl(type)
        val timestamp = nal.timestamp90k?.let(::unwrap)
        rememberParameterSet(type, data)
        val startsNew =
            currentHasVcl &&
                (
                    (vcl && (firstSliceFlag(data) || (timestamp != null && timestamp != currentTimestamp))) ||
                        (!vcl && codec.startsAccessUnit(type))
                )
        val completed = if (startsNew) complete() else null
        if (current.isEmpty()) currentTimestamp = timestamp
        if (vcl) {
            currentHasVcl = true
            if (codec.isIrap(type)) currentKeyframe = true
        }
        current += data
        currentBytes += START_CODE.size + data.size
        if (currentBytes > MAX_ACCESS_UNIT) return completed ?: complete()
        return completed
    }

    /** Returns the picture being assembled, if any (call at end of stream). */
    public fun flush(): AccessUnit? = if (current.isEmpty()) null else complete()

    private fun complete(): AccessUnit {
        val bytes = ByteArray(currentBytes)
        var at = 0
        for (nal in current) {
            START_CODE.copyInto(bytes, at)
            at += START_CODE.size
            nal.copyInto(bytes, at)
            at += nal.size
        }
        val unit =
            AccessUnit(
                codec = codec,
                data = bytes,
                presentationTimeUs = currentTimestamp?.let { it * MICROS_PER_SECOND / TIMESTAMP_HZ },
                isKeyframe = currentKeyframe,
                config = config,
            )
        current.clear()
        currentBytes = 0
        currentHasVcl = false
        currentKeyframe = false
        currentTimestamp = null
        return unit
    }

    private fun rememberParameterSet(
        type: Int,
        data: ByteArray,
    ) {
        when {
            codec.isVps(type) -> vps = START_CODE + data
            codec.isSps(type) -> sps = START_CODE + data
            codec.isPps(type) -> pps = START_CODE + data
        }
    }

    /** First bit of the slice header is set for the first slice of a picture (both codecs). */
    private fun firstSliceFlag(data: ByteArray): Boolean {
        val hdr = codec.nalHeaderLength
        return data.size > hdr && data[hdr].toInt() and FIRST_BIT != 0
    }

    private fun unwrap(raw: Long): Long {
        val last = lastRawTimestamp
        if (last != null) {
            if (raw < last && last - raw > WRAP_THRESHOLD) timestampBase += TIMESTAMP_RANGE
            if (raw > last && raw - last > WRAP_THRESHOLD && timestampBase > 0) timestampBase -= TIMESTAMP_RANGE
        }
        lastRawTimestamp = raw
        return timestampBase + raw
    }

    /** Constants. */
    public companion object {
        /** 4-byte Annex-B start code. */
        public val START_CODE: ByteArray = byteArrayOf(0, 0, 0, 1)
        private const val MAX_ACCESS_UNIT = 2 shl 20
        private const val FIRST_BIT = 0x80
        private const val TIMESTAMP_HZ = 90_000L
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val TIMESTAMP_RANGE = 1L shl 32
        private const val WRAP_THRESHOLD = 1L shl 31
    }
}
