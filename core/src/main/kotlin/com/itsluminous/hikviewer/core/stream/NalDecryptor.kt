package com.itsluminous.hikviewer.core.stream

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the NAL units of a Hik-Connect cloud stream with the device's video verification code.
 *
 * The scheme (reverse-engineered by cloud-cam-viewer and verified on this project's DVR) is
 * AES-128-ECB over the first 4096 bytes of a NAL body, key = the code left-justified with NUL bytes
 * to 16 bytes. What exactly is encrypted differs between cameras, so it is detected from the stream:
 *
 *  * [Variant.CLEAR]: nothing is encrypted;
 *  * [Variant.HEADER_CLEAR]: the NAL header is clear, the body encrypted; IRAP slices and parameter
 *    sets always, inter (P/B) slices only on some cameras, which the sampler below decides;
 *  * [Variant.TYPE_HINT]: a clear copy of the header precedes a fully encrypted NAL;
 *  * [Variant.WHOLE]: the entire NAL (header included) is encrypted.
 *
 * The decision needs a parameter set (H.264 SPS whose decrypted body starts with a `profile_idc`,
 * HEVC VPS whose body has `0x0C` at offset 2) plus a majority of plausible headers, so a chance
 * match on one NAL cannot pick the wrong variant. Until then NALs are buffered (at most
 * [MAX_UNDECIDED]); with enough NALs and no match the code is wrong ([keyError]).
 *
 * Two more behaviours matter for a clean first picture: slices before the first IRAP are dropped
 * (the cloud starts mid-GOP and resends parameter sets periodically, so forwarding the first P-slices
 * would leave the decoder without references); and whether inter slices are encrypted is decided
 * from a structural test, not guessed (slice headers repeat, ciphertext does not), because a wrong
 * guess corrupts every P-frame. Pure: no I/O, not thread-safe.
 */
public class NalDecryptor(
    verificationCode: String?,
    codecHint: VideoCodec? = null,
    /** Cached decision whether inter slices are encrypted; skips the buffering, is verified anyway. */
    interEncryptedHint: Boolean? = null,
) {
    /** How this stream is encrypted. */
    public enum class Variant { CLEAR, HEADER_CLEAR, TYPE_HINT, WHOLE }

    private val cipher: Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes(verificationCode.orEmpty()), "AES"))
        }
    private val codecCandidates = codecHint?.let { listOf(it) } ?: VideoCodec.entries

    /** Codec detected from the parameter sets, or null while undecided. */
    public var codec: VideoCodec? = null
        private set

    /** Encryption variant, or null while undecided. */
    public var variant: Variant? = null
        private set

    /** True once enough NALs arrived without any variant matching: the verification code is wrong. */
    public var keyError: Boolean = false
        private set

    /**
     * What the undecided NALs looked like when [keyError] was raised (count, sizes, first bytes), so
     * a false positive on a real recorder can be diagnosed from a log line.
     */
    public var keyErrorDetail: String? = null
        private set

    /** Whether inter slices are encrypted (HEADER_CLEAR only); null while sampling. */
    public var interEncrypted: Boolean? = interEncryptedHint
        private set

    /** Slices dropped before the first IRAP. */
    public var gatedSlices: Int = 0
        private set

    private var verifyingHint = interEncryptedHint != null
    private val undecided = ArrayList<NalUnit>()
    private var pending: ArrayList<Pending>? = null
    private var pendingBytes = 0
    private val interClearSamples = ArrayList<Int>()
    private val interDecryptedSamples = ArrayList<Int>()
    private var seenIrap = false

    private sealed class Pending(
        val nal: NalUnit,
    ) {
        class Ready(
            nal: NalUnit,
        ) : Pending(nal)

        class Inter(
            nal: NalUnit,
        ) : Pending(nal)
    }

    /** Feeds one NAL; returns the decrypted NALs that can be released (possibly buffered ones). */
    public fun feed(nal: NalUnit): List<NalUnit> {
        if (keyError || nal.size == 0) return emptyList()
        val out = ArrayList<NalUnit>()
        if (variant == null) {
            undecided += nal
            if (undecided.size > MAX_UNDECIDED) undecided.subList(0, undecided.size - MAX_UNDECIDED).clear()
            val decision = decide() ?: return out
            if (decision === KEY_ERROR) {
                keyError = true
                keyErrorDetail = describeUndecided()
                undecided.clear()
                return out
            }
            codec = decision.codec
            variant = decision.variant
            pending = if (decision.variant == Variant.HEADER_CLEAR && !verifyingHint) ArrayList() else null
            undecided.forEach { emit(it, out) }
            undecided.clear()
            return out
        }
        emit(nal, out)
        return out
    }

    private fun describeUndecided(): String {
        val heads = undecided.groupingBy { "%02x".format(it.data[0]) }.eachCount()
        val sizes = undecided.map { it.size }
        val topHeads = heads.entries.sortedByDescending { it.value }.take(HEADS_IN_DETAIL)
        return "nals=${undecided.size} sizes=${sizes.minOrNull()}..${sizes.maxOrNull()} " +
            "heads=" + topHeads.joinToString(",") { "${it.key}x${it.value}" }
    }

    // ---- variant detection -------------------------------------------------------------------

    private class Decision(
        val codec: VideoCodec,
        val variant: Variant,
    )

    private fun decide(): Decision? {
        val big = undecided.filter { it.size >= MIN_DECIDABLE_NAL }
        if (big.size < MIN_DECIDABLE_COUNT) return null
        for (codec in codecCandidates) {
            matchVariant(codec, big)?.let { return it }
        }
        if (big.size < MIN_KEY_ERROR_COUNT) return null
        // Enough NALs and nothing matched. That is a wrong key only if we really saw a codec's parameter
        // sets (two kinds, so an H.264 P-slice header misread as an HEVC VPS does not count); a stream
        // that started mid-GOP with plausible clear headers just has not reached its SPS/PPS yet.
        val sawParameterSets = codecCandidates.any { c -> cleanParameterSetKinds(c).size >= 2 }
        val headersPlausible = codecCandidates.any { c -> corroborated(c, big.map { it.data[0] }) }
        return if (!sawParameterSets && headersPlausible) null else KEY_ERROR
    }

    private fun cleanParameterSetKinds(codec: VideoCodec): Set<Int> = undecided.mapNotNullTo(HashSet()) { unit ->
        val b = unit.data[0]
        if (b.toInt() and FORBIDDEN_BIT != 0) return@mapNotNullTo null
        codec.nalType(b).takeIf { codec.isParameterSet(it) }
    }

    private fun matchVariant(
        codec: VideoCodec,
        big: List<NalUnit>,
    ): Decision? {
        val hdr = codec.nalHeaderLength
        val rawHeaders = big.map { it.data[0] }
        val wholeDecrypted = big.map { aesBlock(it.data, 0) }
        for ((i, unit) in big.withIndex()) {
            val n = unit.data
            if (isCleanParameterSet(codec, n)) {
                if (marker(codec, n, hdr) && corroborated(codec, rawHeaders)) return Decision(codec, Variant.CLEAR)
                if (marker(codec, aesBlock(n, hdr), 0) && corroborated(codec, rawHeaders)) {
                    return Decision(codec, Variant.HEADER_CLEAR)
                }
            }
            val hinted = aesBlock(n, hdr)
            if (isParameterSetHeader(codec, hinted[0]) &&
                marker(codec, hinted, hdr) &&
                corroborated(codec, rawHeaders)
            ) {
                return Decision(codec, Variant.TYPE_HINT)
            }
            val whole = wholeDecrypted[i]
            if (isParameterSetHeader(codec, whole[0]) &&
                marker(codec, whole, hdr) &&
                corroborated(codec, wholeDecrypted.map { it[0] })
            ) {
                return Decision(codec, Variant.WHOLE)
            }
        }
        return null
    }

    /** SPS (H.264) or VPS (HEVC) with a clear header. */
    private fun isCleanParameterSet(
        codec: VideoCodec,
        n: ByteArray,
    ): Boolean = isParameterSetHeader(codec, n[0]) && n.size > codec.nalHeaderLength

    private fun isParameterSetHeader(
        codec: VideoCodec,
        b: Byte,
    ): Boolean {
        if (b.toInt() and FORBIDDEN_BIT != 0) return false
        val type = codec.nalType(b)
        return if (codec == VideoCodec.H264) codec.isSps(type) else codec.isVps(type)
    }

    /**
     * Does a parameter-set body start at [at] in [bytes]? H.264 SPS: `profile_idc` from the known list,
     * `reserved_zero_2bits` clear, a known `level_idc`. HEVC VPS: `0x0C` then the `0xFFFF` reserved
     * bits. Checking three bytes instead of one keeps a random ciphertext byte (about a 5 % chance of
     * landing on a profile id) from being mistaken for a clear SPS.
     */
    private fun marker(
        codec: VideoCodec,
        bytes: ByteArray,
        at: Int,
    ): Boolean {
        if (bytes.size < at + MARKER_LENGTH) return false
        val b0 = bytes[at].toInt() and BYTE_MASK
        val b1 = bytes[at + 1].toInt() and BYTE_MASK
        val b2 = bytes[at + 2].toInt() and BYTE_MASK
        return if (codec == VideoCodec.H264) {
            b0 in VideoCodec.H264_PROFILES && b1 and H264_RESERVED_ZERO_2BITS == 0 && b2 in H264_LEVELS
        } else {
            b0 == VideoCodec.HEVC_VPS_MARKER && b2 == BYTE_MASK
        }
    }

    private fun corroborated(
        codec: VideoCodec,
        headers: List<Byte>,
    ): Boolean {
        val hits = headers.count { plausibleHeader(codec, it) }
        return hits >= maxOf(2, headers.size / 2)
    }

    private fun plausibleHeader(
        codec: VideoCodec,
        b: Byte,
    ): Boolean {
        if (b.toInt() and FORBIDDEN_BIT != 0) return false
        val type = codec.nalType(b)
        return if (codec == VideoCodec.H264) type in PLAUSIBLE_H264 else type in PLAUSIBLE_HEVC
    }

    // ---- emission ---------------------------------------------------------------------------

    private fun emit(
        nal: NalUnit,
        out: MutableList<NalUnit>,
    ) {
        val codec = codec ?: return
        val hdr = codec.nalHeaderLength
        val n = nal.data
        when (variant) {
            Variant.CLEAR -> if (irapGate(codec, codec.nalType(n[0]))) put(nal, out)
            Variant.WHOLE -> put(nal.withData(decryptBody(n, 0)), out)
            Variant.TYPE_HINT -> put(nal.withData(stripTypeHint(n, hdr)), out)
            Variant.HEADER_CLEAR -> emitHeaderClear(nal, codec, out)
            null -> Unit
        }
    }

    /**
     * TYPE_HINT: an encrypted NAL carries a clear copy of its header first (decrypting the next block
     * reproduces it); a short clear NAL carries a duplicated header; a plain clear NAL (P-slice)
     * carries nothing extra. The header is encrypted so the IRAP gate cannot apply here.
     */
    private fun stripTypeHint(
        n: ByteArray,
        hdr: Int,
    ): ByteArray = when {
        n.size >= hdr + AES_BLOCK && aesBlock(n, hdr).copyOf(hdr).contentEquals(n.copyOf(hdr)) ->
            decryptBody(n, hdr)
        n.size >= 2 * hdr && n.copyOfRange(hdr, 2 * hdr).contentEquals(n.copyOf(hdr)) -> n.copyOfRange(hdr, n.size)
        else -> n
    }

    private fun emitHeaderClear(
        nal: NalUnit,
        codec: VideoCodec,
        out: MutableList<NalUnit>,
    ) {
        val n = nal.data
        val hdr = codec.nalHeaderLength
        if (n.size <= hdr) {
            put(nal, out)
            return
        }
        val type = codec.nalType(n[0])
        val alwaysEncrypted = codec.isEncryptedType(type)
        // Sample regardless of the IRAP gate so the decision is ready when the gate opens.
        if (!alwaysEncrypted &&
            codec.isInterSlice(type) &&
            (interEncrypted == null || verifyingHint)
        ) {
            sampleInter(n, hdr)
        }
        if (interEncrypted != null && pending != null) flushPending(out)
        if (!irapGate(codec, type)) return
        if (alwaysEncrypted) {
            put(nal.withData(n.copyOf(hdr) + decryptBody(n, hdr)), out)
        } else {
            putInter(nal, out)
        }
    }

    private fun irapGate(
        codec: VideoCodec,
        type: Int,
    ): Boolean {
        if (seenIrap) return true
        if (codec.isIrap(type)) {
            seenIrap = true
            return true
        }
        if (codec.isParameterSet(type)) return true
        gatedSlices++
        if (gatedSlices > MAX_GATED_SLICES) seenIrap = true // never-marked IRAP: show something anyway
        return seenIrap
    }

    private fun put(
        nal: NalUnit,
        out: MutableList<NalUnit>,
    ) {
        val queue = pending
        if (queue == null) {
            out += nal
            return
        }
        queue += Pending.Ready(nal)
        pendingBytes += nal.size
        guardPending(out)
    }

    private fun putInter(
        nal: NalUnit,
        out: MutableList<NalUnit>,
    ) {
        val queue = pending
        if (queue == null) {
            out += interBytes(nal)
            return
        }
        queue += Pending.Inter(nal)
        pendingBytes += nal.size
        if (interEncrypted != null) flushPending(out) else guardPending(out)
    }

    private fun interBytes(nal: NalUnit): NalUnit {
        val hdr = codec!!.nalHeaderLength
        return if (interEncrypted == true) nal.withData(nal.data.copyOf(hdr) + decryptBody(nal.data, hdr)) else nal
    }

    /** Only keyframes arriving (no inter slice at all): stop waiting and assume clear inter slices. */
    private fun guardPending(out: MutableList<NalUnit>) {
        if (pending != null && pendingBytes > MAX_PENDING_BYTES) {
            interEncrypted = false
            flushPending(out)
        }
    }

    private fun flushPending(out: MutableList<NalUnit>) {
        val queue = pending ?: return
        pending = null
        pendingBytes = 0
        queue.forEach { out += if (it is Pending.Inter) interBytes(it.nal) else it.nal }
    }

    /**
     * Structure test: slice-header bytes repeat across P-slices (same PPS, same layout) while
     * ciphertext is random, so the reading with fewer distinct first bytes is the right one.
     */
    private fun sampleInter(
        n: ByteArray,
        hdr: Int,
    ) {
        if (n.size - hdr < AES_BLOCK) return
        interClearSamples += n[hdr].toInt() and BYTE_MASK
        interDecryptedSamples += aesBlock(n, hdr)[0].toInt() and BYTE_MASK
        if (interClearSamples.size >= INTER_SAMPLES) decideInter()
    }

    private fun decideInter() {
        val count = interClearSamples.size.toDouble()
        val distinctClear = interClearSamples.toSet().size / count
        val distinctDecrypted = interDecryptedSamples.toSet().size / count
        // A tie means clear: decrypting a clear P-slice destroys it, the reverse only blurs it.
        val encrypted = distinctDecrypted + INTER_MARGIN < distinctClear
        verifyingHint = false
        interEncrypted = encrypted
    }

    // ---- AES helpers ------------------------------------------------------------------------

    private fun aesBlock(
        n: ByteArray,
        at: Int,
    ): ByteArray = cipher.doFinal(n, at, AES_BLOCK)

    /** Decrypts whole 16-byte blocks of the first 4096 bytes after [from]; the rest is clear. */
    private fun decryptBody(
        n: ByteArray,
        from: Int,
    ): ByteArray {
        val bodyLength = n.size - from
        val encryptedLength = (minOf(bodyLength, ENCRYPTED_PREFIX) / AES_BLOCK) * AES_BLOCK
        if (encryptedLength == 0) return n.copyOfRange(from, n.size)
        val out = ByteArray(bodyLength)
        cipher.doFinal(n, from, encryptedLength, out, 0)
        n.copyInto(out, encryptedLength, from + encryptedLength, n.size)
        return out
    }

    /** Key derivation and tuning constants. */
    public companion object {
        /** AES block length. */
        public const val AES_BLOCK: Int = 16

        /** Only this many leading body bytes are encrypted per NAL. */
        public const val ENCRYPTED_PREFIX: Int = 4096
        private const val MAX_UNDECIDED = 300
        private const val HEADS_IN_DETAIL = 6
        private const val MIN_DECIDABLE_NAL = 18
        private const val MIN_DECIDABLE_COUNT = 3
        private const val MIN_KEY_ERROR_COUNT = 24
        private const val MAX_GATED_SLICES = 400
        private const val INTER_SAMPLES = 8
        private const val INTER_MARGIN = 0.25
        private const val MAX_PENDING_BYTES = 4 shl 20
        private const val FORBIDDEN_BIT = 0x80
        private const val BYTE_MASK = 0xFF
        private const val MARKER_LENGTH = 3
        private const val H264_RESERVED_ZERO_2BITS = 0x03
        private val H264_LEVELS =
            setOf(9, 10, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52, 60, 61, 62)
        private val PLAUSIBLE_H264 = setOf(1, 5, 6, 7, 8, 9)
        private val PLAUSIBLE_HEVC = setOf(0, 1, 2, 4, 19, 20, 21, 32, 33, 34, 39, 40)
        private val KEY_ERROR = Decision(VideoCodec.H264, Variant.CLEAR)

        /** The 16-byte AES key for a verification code: UTF-8 bytes, NUL-padded, truncated. */
        public fun keyBytes(verificationCode: String): ByteArray =
            verificationCode.toByteArray(Charsets.UTF_8).copyOf(AES_BLOCK)
    }
}
