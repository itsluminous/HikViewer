package com.itsluminous.hikviewer.core.stream

/**
 * Rewrites an H.264 SPS so decoders output every picture as soon as it is decoded.
 *
 * The recorder's SPS (High profile, level 3.1, `pic_order_cnt_type` 0) has no VUI
 * `bitstream_restriction`, so a decoder must assume the worst-case reordering the level allows and
 * holds about ten pictures before showing the first one. The MiTV's software decoder
 * (`OMX.google.h264.decoder`, libavc) does exactly that, which costs every software tile ~0.9 s of
 * latency and, worse, wedges it: the recorder resets the picture order count to 0 at every IDR
 * while ten pictures of the previous GOP are still queued for display, and libavc's display logic
 * drops and reorders pictures around that boundary until it stops producing output altogether
 * while still consuming input (seen as "decoder stalled" every 30–90 s per tile on the TV). The
 * stream has no B-frames and its order count grows within a GOP, so decode order *is* display
 * order: declaring `max_num_reorder_frames = 0` is both true and what stops the decoder from
 * reordering. `max_dec_frame_buffering` is set to the SPS's own reference frame count.
 *
 * The rest of the SPS is copied bit for bit (a VUI already present keeps every field); a NAL that
 * is not an SPS, or one this parser cannot follow, is returned unchanged. Pure Kotlin: the parse is
 * a straight transcription of H.264 7.3.2.1.1 / E.1.1 and is covered by tests against the
 * recorder's real SPS.
 */
public object H264SpsRewriter {
    /**
     * [nal] is an SPS NAL (header byte + RBSP, no start code); returns the rewritten NAL, or [nal]
     * itself when it is not an H.264 SPS or cannot be parsed.
     */
    public fun withoutReordering(nal: ByteArray): ByteArray {
        if (nal.isEmpty() || nal[0].toInt() and NAL_TYPE_MASK != SPS_TYPE) return nal
        return try {
            rewrite(nal)
        } catch (_: IndexOutOfBoundsException) {
            nal
        } catch (_: IllegalStateException) {
            nal
        }
    }

    private fun rewrite(nal: ByteArray): ByteArray {
        val t = Transcoder(unescape(nal, 1))
        val profileIdc = t.bits(BYTE)
        t.bits(BYTE) // constraint_set flags + reserved
        t.bits(BYTE) // level_idc
        t.ue() // seq_parameter_set_id
        if (profileIdc.toInt() in HIGH_PROFILES) t.copyHighProfileFields()
        t.ue() // log2_max_frame_num_minus4
        t.copyPicOrderCount()
        val numRefFrames = t.ue()
        t.bit() // gaps_in_frame_num_value_allowed_flag
        t.ue() // pic_width_in_mbs_minus1
        t.ue() // pic_height_in_map_units_minus1
        if (t.bit() == 0) t.bit() // frame_mbs_only_flag == 0 → mb_adaptive_frame_field_flag
        t.bit() // direct_8x8_inference_flag
        if (t.bit() == 1) repeat(CROP_FIELDS) { t.ue() }
        val vuiPresent = t.readBit()
        t.writeBit(1)
        if (vuiPresent == 1) t.copyVuiUpToRestriction() else t.writeEmptyVui()
        t.writeRestriction(numRefFrames)
        t.writeTrailingBits()
        return byteArrayOf(nal[0]) + escape(t.output())
    }

    private fun Transcoder.copyHighProfileFields() {
        val chromaFormatIdc = ue()
        if (chromaFormatIdc == CHROMA_444) bit() // separate_colour_plane_flag
        ue() // bit_depth_luma_minus8
        ue() // bit_depth_chroma_minus8
        bit() // qpprime_y_zero_transform_bypass_flag
        if (bit() == 0) return // seq_scaling_matrix_present_flag
        val lists = if (chromaFormatIdc != CHROMA_444) SCALING_LISTS_420 else SCALING_LISTS_444
        for (i in 0 until lists) {
            if (bit() == 1) scalingList(if (i < SCALING_LISTS_4X4) SCALING_4X4 else SCALING_8X8)
        }
    }

    private fun Transcoder.copyPicOrderCount() {
        when (ue()) {
            0 -> ue() // log2_max_pic_order_cnt_lsb_minus4
            1 -> {
                bit() // delta_pic_order_always_zero_flag
                se() // offset_for_non_ref_pic
                se() // offset_for_top_to_bottom_field
                repeat(ue()) { se() } // offset_for_ref_frame
            }
        }
    }

    /** The `bitstream_restriction` block that declares decode order = display order. */
    private fun Transcoder.writeRestriction(numRefFrames: Int) {
        writeBit(1) // bitstream_restriction_flag
        writeBit(1) // motion_vectors_over_pic_boundaries_flag
        writeUe(0) // max_bytes_per_pic_denom (0 = no limit)
        writeUe(0) // max_bits_per_mb_denom (0 = no limit)
        writeUe(MAX_MV_LENGTH_LOG2) // log2_max_mv_length_horizontal
        writeUe(MAX_MV_LENGTH_LOG2) // log2_max_mv_length_vertical
        writeUe(0) // max_num_reorder_frames
        writeUe(maxOf(numRefFrames, 1)) // max_dec_frame_buffering
    }

    /** Copies an existing VUI up to (and excluding) `bitstream_restriction_flag` and its fields. */
    private fun Transcoder.copyVuiUpToRestriction() {
        if (bit() == 1) { // aspect_ratio_info_present_flag
            if (bits(BYTE) == EXTENDED_SAR) bits(SAR_BITS)
        }
        if (bit() == 1) bit() // overscan_info_present_flag → overscan_appropriate_flag
        if (bit() == 1) { // video_signal_type_present_flag
            bits(VIDEO_FORMAT_AND_RANGE_BITS)
            if (bit() == 1) bits(COLOUR_DESCRIPTION_BITS)
        }
        if (bit() == 1) { // chroma_loc_info_present_flag
            ue()
            ue()
        }
        if (bit() == 1) { // timing_info_present_flag
            bits(WORD)
            bits(WORD)
            bit()
        }
        val nalHrd = bit()
        if (nalHrd == 1) hrd()
        val vclHrd = bit()
        if (vclHrd == 1) hrd()
        if (nalHrd == 1 || vclHrd == 1) bit() // low_delay_hrd_flag
        bit() // pic_struct_present_flag
        if (readBit() == 1) { // bitstream_restriction_flag: drop the old fields, ours follow
            readBit()
            repeat(RESTRICTION_UE_FIELDS) { readUe() }
        }
    }

    /** A VUI with every optional block absent; only the restriction the caller appends. */
    private fun Transcoder.writeEmptyVui() = repeat(VUI_PRESENCE_FLAGS) { writeBit(0) }

    private fun Transcoder.hrd() {
        val cpbCount = ue() + 1
        bits(NIBBLE) // bit_rate_scale
        bits(NIBBLE) // cpb_size_scale
        repeat(cpbCount) {
            ue()
            ue()
            bit()
        }
        repeat(HRD_DELAY_FIELDS) { bits(HRD_DELAY_BITS) }
    }

    private fun Transcoder.scalingList(size: Int) {
        var last = SCALING_DEFAULT
        var next = SCALING_DEFAULT
        repeat(size) {
            if (next != 0) next = (last + se() + SCALING_MOD) % SCALING_MOD
            last = if (next == 0) last else next
        }
    }

    /** Removes emulation prevention bytes (`00 00 03` → `00 00`) from [nal] after [from]. */
    private fun unescape(
        nal: ByteArray,
        from: Int,
    ): ByteArray {
        val out = ByteArray(nal.size - from)
        var n = 0
        var zeros = 0
        for (i in from until nal.size) {
            val b = nal[i].toInt() and BYTE_MASK
            if (zeros >= 2 && b == EMULATION_PREVENTION) {
                zeros = 0
                continue
            }
            out[n++] = nal[i]
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.copyOf(n)
    }

    /** Inserts emulation prevention bytes so no `00 00 0x` (x ≤ 3) appears in the payload. */
    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArray(rbsp.size + rbsp.size / 2 + 1)
        var n = 0
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and BYTE_MASK
            if (zeros >= 2 && v <= EMULATION_PREVENTION) {
                out[n++] = EMULATION_PREVENTION.toByte()
                zeros = 0
            }
            out[n++] = b
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.copyOf(n)
    }

    /**
     * Reads an RBSP bit by bit while writing a copy; the `read*` variants read without copying and
     * the `write*` variants write without reading, for the fields that change.
     */
    private class Transcoder(
        private val input: ByteArray,
    ) {
        private var readPos = 0
        private val out = ArrayList<Byte>()
        private var current = 0
        private var currentBits = 0

        fun readBit(): Int {
            val byte = readPos ushr BITS_PER_BYTE_SHIFT
            check(byte < input.size) { "SPS ends early" }
            val bit = (input[byte].toInt() ushr (BITS_LAST - (readPos and BITS_INDEX_MASK))) and 1
            readPos++
            return bit
        }

        fun readBits(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 1) or readBit().toLong() }
            return v
        }

        fun readUe(): Int {
            var zeros = 0
            while (readBit() == 0) {
                zeros++
                check(zeros <= MAX_UE_ZEROS) { "bad Exp-Golomb code" }
            }
            return ((1L shl zeros) - 1 + readBits(zeros)).toInt()
        }

        fun writeBit(bit: Int) {
            current = (current shl 1) or (bit and 1)
            if (++currentBits == BYTE) {
                out += current.toByte()
                current = 0
                currentBits = 0
            }
        }

        fun writeBits(
            value: Long,
            n: Int,
        ) {
            for (i in n - 1 downTo 0) writeBit(((value ushr i) and 1L).toInt())
        }

        fun writeUe(value: Int) {
            val v = value.toLong() + 1
            val length = Long.SIZE_BITS - java.lang.Long.numberOfLeadingZeros(v)
            repeat(length - 1) { writeBit(0) }
            writeBits(v, length)
        }

        fun writeTrailingBits() {
            writeBit(1)
            while (currentBits != 0) writeBit(0)
        }

        fun bit(): Int = readBit().also(::writeBit)

        fun bits(n: Int): Long = readBits(n).also { writeBits(it, n) }

        fun ue(): Int = readUe().also(::writeUe)

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }

        fun output(): ByteArray = out.toByteArray()
    }

    private const val NAL_TYPE_MASK = 0x1F
    private const val SPS_TYPE = 7
    private const val BYTE = 8
    private const val NIBBLE = 4
    private const val WORD = 32
    private const val BYTE_MASK = 0xFF
    private const val EMULATION_PREVENTION = 3
    private const val CHROMA_444 = 3
    private const val SCALING_LISTS_420 = 8
    private const val SCALING_LISTS_444 = 12
    private const val SCALING_LISTS_4X4 = 6
    private const val SCALING_4X4 = 16
    private const val SCALING_8X8 = 64
    private const val SCALING_DEFAULT = 8
    private const val SCALING_MOD = 256
    private const val CROP_FIELDS = 4
    private const val EXTENDED_SAR = 255L
    private const val SAR_BITS = 32
    private const val VIDEO_FORMAT_AND_RANGE_BITS = 4
    private const val COLOUR_DESCRIPTION_BITS = 24
    private const val HRD_DELAY_FIELDS = 4
    private const val HRD_DELAY_BITS = 5
    private const val RESTRICTION_UE_FIELDS = 6
    private const val VUI_PRESENCE_FLAGS = 8
    private const val MAX_MV_LENGTH_LOG2 = 16
    private const val BITS_PER_BYTE_SHIFT = 3
    private const val BITS_INDEX_MASK = 7
    private const val BITS_LAST = 7
    private const val MAX_UE_ZEROS = 31
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
}
