package com.itsluminous.hikviewer.core.stream

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.hikviewer.core.testutil.Fixtures
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Known-answer tests for [NalDecryptor].
 *
 * Fixtures `h264_nals_*.bin` are the first GOP of the synthetic clip `synthetic_sub_2s5.ts` (ffmpeg's
 * `testsrc2`, x264 High 960x480 @ 12 fps; `ffmpeg -c copy -f h264`), encrypted by `scripts/gen-fixtures.sh`
 * with cloud-cam-viewer's algorithm (`src/cloudcam/codec/nal.py`): AES-128-ECB, key =
 * `"ABCDEF1234".encode().ljust(16, b"\0")` (a dummy verification code), encrypting the first
 * `(min(len, 4096) // 16) * 16` bytes of a NAL body. Each file is a sequence of u32-BE length-prefixed
 * NAL units (no start codes, so ciphertext containing `00 00 01` cannot split a NAL):
 *  * `clear`: AUD, SPS, PPS, IDR, then 9 × (AUD, P-slice), the first ten pictures as encoded;
 *  * `header_clear_selective`: IDR/SPS/PPS bodies encrypted, P-slices clear (variant A, selective);
 *  * `header_clear_all`: every slice body encrypted too (variant A, full);
 *  * `whole`: the entire NAL including its header (variant B-whole);
 *  * `type_hint`: a clear header byte copy followed by the whole encrypted NAL (variant B-hint).
 * Bodies shorter than one AES block (the AUDs, the PPS) stay clear in every variant, as on a recorder.
 * The Kotlin [encryptBody] helper below is the same algorithm and is used for the HEVC cases.
 */
class NalDecryptorTest {
    private val clear = readUnits("h264_nals_clear.bin")

    /** The IRAP gate drops the access-unit delimiter that precedes the first SPS, as the reference does. */
    private val clearAfterGate = clear.drop(1)

    private fun run(
        decryptor: NalDecryptor,
        units: List<ByteArray>,
    ): List<ByteArray> = units.flatMap { decryptor.feed(NalUnit(it, 0L)) }.map { it.data }

    private fun assertSameNals(
        actual: List<ByteArray>,
        expected: List<ByteArray>,
    ) {
        assertThat(actual.size).isEqualTo(expected.size)
        actual.zip(expected).forEachIndexed { i, (a, e) -> assertWithMessage("nal $i").that(a).isEqualTo(e) }
    }

    @Nested
    inner class H264Vectors {
        @Test
        fun `clear stream is detected and passed through unchanged`() {
            val decryptor = NalDecryptor(KEY)

            val out = run(decryptor, clear)

            assertThat(decryptor.codec).isEqualTo(VideoCodec.H264)
            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.CLEAR)
            assertThat(decryptor.keyError).isFalse()
            assertSameNals(out, clearAfterGate)
            assertThat(decryptor.gatedSlices).isEqualTo(1)
        }

        @Test
        fun `header-clear selective encryption decrypts keyframes and leaves P-slices alone`() {
            val decryptor = NalDecryptor(KEY)

            val out = run(decryptor, readUnits("h264_nals_header_clear_selective.bin"))

            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.HEADER_CLEAR)
            assertThat(decryptor.interEncrypted).isFalse()
            assertSameNals(out, clearAfterGate)
        }

        @Test
        fun `header-clear full encryption decrypts P-slices too`() {
            val decryptor = NalDecryptor(KEY)

            val out = run(decryptor, readUnits("h264_nals_header_clear_all.bin"))

            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.HEADER_CLEAR)
            assertThat(decryptor.interEncrypted).isTrue()
            assertSameNals(out, clearAfterGate)
        }

        @Test
        fun `whole-NAL encryption`() {
            val decryptor = NalDecryptor(KEY)

            val out = run(decryptor, readUnits("h264_nals_whole.bin"))

            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.WHOLE)
            assertSameNals(out, clear)
        }

        @Test
        fun `type-hint encryption`() {
            val decryptor = NalDecryptor(KEY)

            val out = run(decryptor, readUnits("h264_nals_type_hint.bin"))

            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.TYPE_HINT)
            assertSameNals(out, clear)
        }

        /**
         * The recorder sends most P-slices in the clear under TYPE_HINT, and a clear slice's first
         * block decrypts to its own header byte by chance once in 256. Such a slice must not be
         * "decrypted" into garbage once the decryptor has seen that its header class is clear.
         */
        @Test
        fun `type-hint chance probe match on a clear slice is passed through once its class is known clear`() {
            val decryptor = NalDecryptor(KEY)
            val pSlices = clear.filter { it[0] == 0x41.toByte() }
            val hinted = clear.take(4).map(::hint)
            val collision = probeCollision(pSlices[0])
            val longTerm = pSlices.take(2).map { it.copyOf().also { n -> n[0] = 0x61 } } // an encrypted header class
            val units =
                hinted + pSlices.take(8) + listOf(collision) +
                    longTerm.map { it.copyOf(1) + encryptBody(it, 0) } + listOf(collision)

            val out = run(decryptor, units)

            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.TYPE_HINT)
            val expected = clear.take(4) + pSlices.take(8) + listOf(collision) + longTerm + listOf(collision)
            assertSameNals(out, expected)
        }

        @Test
        fun `type-hint probe still decides before a header class is known`() {
            val decryptor = NalDecryptor(KEY)
            val pSlices = clear.filter { it[0] == 0x41.toByte() }
            val hinted = clear.take(4).map(::hint)
            val collision = probeCollision(pSlices[0])

            val out = run(decryptor, hinted + pSlices.take(2) + listOf(collision))

            // The third slice of its class: no evidence yet, the matching probe is believed (and wrong).
            val last = out.last()
            assertThat(last.size).isEqualTo(collision.size - 1)
            assertThat(last[0]).isEqualTo(0x41.toByte())
            assertThat(last.copyOfRange(1, 16)).isNotEqualTo(collision.copyOfRange(2, 17))
        }

        /** TYPE_HINT framing: a clear header byte, then the whole NAL encrypted (short NALs stay clear). */
        private fun hint(nal: ByteArray): ByteArray = nal.copyOf(1) + if (nal.size > 16) encryptBody(nal, 0) else nal

        /** A clear slice whose first body block AES-decrypts to its own header byte, as one in 256 does. */
        private fun probeCollision(slice: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(NalDecryptor.keyBytes(KEY), "AES"))
            val plain = ByteArray(16) { (it * 37 + 11).toByte() }.also { it[0] = slice[0] }
            return slice.copyOf(1) + cipher.doFinal(plain) + slice.copyOfRange(17, slice.size)
        }

        @Test
        fun `a cached inter decision skips the buffering and is still verified`() {
            val wrongHint = NalDecryptor(KEY, interEncryptedHint = true)
            val rightHint = NalDecryptor(KEY, interEncryptedHint = false)
            // Fresh arrays per decryptor: a fed NAL is decrypted in place and belongs to the decryptor.
            val outWrong = run(wrongHint, readUnits("h264_nals_header_clear_selective.bin"))
            val outRight = run(rightHint, readUnits("h264_nals_header_clear_selective.bin"))

            assertThat(rightHint.interEncrypted).isFalse()
            assertSameNals(outRight, clearAfterGate)
            // With the wrong hint the first P-slices were "decrypted" (garbage) before the sampler corrected it.
            assertThat(wrongHint.interEncrypted).isFalse()
            assertThat(outWrong.size).isEqualTo(clearAfterGate.size)
            assertThat(outWrong.last()).isEqualTo(clearAfterGate.last())
            assertThat(outWrong[4]).isNotEqualTo(clearAfterGate[4])
        }

        @Test
        fun `wrong key is reported once enough NALs were seen`() {
            val decryptor = NalDecryptor("WRONGKEY")

            val whole = readUnits("h264_nals_whole.bin")
            val out = run(decryptor, whole + whole + whole)

            assertThat(decryptor.keyError).isTrue()
            assertThat(out).isEmpty()
            assertThat(decryptor.feed(NalUnit(byteArrayOf(1, 2, 3), 0))).isEmpty()
        }

        @Test
        fun `wrong key on header-clear stream is detected from the clear SPS header`() {
            val decryptor = NalDecryptor("WRONGKEY")

            val all = readUnits("h264_nals_header_clear_all.bin")
            run(decryptor, all + all + all)

            assertThat(decryptor.keyError).isTrue()
        }

        @Test
        fun `codec hint restricts detection`() {
            val decryptor = NalDecryptor(KEY, codecHint = VideoCodec.H265)

            val whole = readUnits("h264_nals_whole.bin")
            val out = run(decryptor, whole + whole + whole)

            assertThat(out).isEmpty()
            assertThat(decryptor.variant).isNull()
        }

        @Test
        fun `stream starting mid-GOP waits for the parameter set and drops slices before the IDR`() {
            val decryptor = NalDecryptor(KEY)
            val units = readUnits("h264_nals_header_clear_selective.bin")
            val pSlices = units.drop(4) // 9 x (AUD, P)
            val reordered = pSlices + pSlices + pSlices + units

            val out = run(decryptor, reordered)

            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.HEADER_CLEAR)
            // 27 P-slices and their 27 AUDs plus the AUD right before the SPS are dropped by the IRAP gate.
            assertThat(decryptor.gatedSlices).isEqualTo(55)
            val types = out.map { it[0].toInt() and 0x1F }
            assertThat(types.first { it != 9 }).isEqualTo(7)
            assertThat(types).containsAtLeast(7, 8, 5, 1).inOrder()
            assertSameNals(out.takeLast(18), clear.drop(4))
        }
    }

    @Nested
    inner class HevcStreams {
        private val vpsBody =
            byteArrayOf(0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte()) + ByteArray(28) { (it + 3).toByte() }
        private val spsBody = byteArrayOf(0x01, 0x01, 0x60, 0x00) + ByteArray(28) { (it * 7 + 1).toByte() }
        private val ppsBody = byteArrayOf(0xC1.toByte(), 0x72, 0xB4.toByte()) + ByteArray(29) { (it + 40).toByte() }

        private fun hevc(
            type: Int,
            body: ByteArray,
        ) = byteArrayOf((type shl 1).toByte(), 0x01) + body

        private fun idr(i: Int) = hevc(
            19,
            byteArrayOf(0xAF.toByte(), i.toByte()) + ByteArray(40) { (it * 13 + i).toByte() },
        )

        /** Clear P-slice: slice header bytes repeat (same PPS), the rest varies. */
        private fun trail(i: Int) = hevc(
            1,
            byteArrayOf(0xD0.toByte(), 0x11, i.toByte()) + ByteArray(40) { (it * 31 + i * 7).toByte() },
        )

        private fun headerClear(
            nal: ByteArray,
            encrypt: Boolean,
        ) = if (encrypt) nal.copyOf(2) + encryptBody(nal, 2) else nal

        private fun stream(interEncrypted: Boolean): List<ByteArray> {
            val head =
                listOf(
                    hevc(32, vpsBody),
                    hevc(33, spsBody),
                    hevc(34, ppsBody),
                    idr(0),
                ).map { headerClear(it, true) }
            val inter = (1..10).map { headerClear(trail(it), interEncrypted) }
            return head + inter
        }

        private val expected =
            listOf(hevc(32, vpsBody), hevc(33, spsBody), hevc(34, ppsBody), idr(0)) + (1..10).map { trail(it) }

        @ParameterizedTest
        @ValueSource(booleans = [false, true])
        fun `header-clear HEVC with clear or encrypted inter slices`(interEncrypted: Boolean) {
            val decryptor = NalDecryptor(KEY)

            val out = run(decryptor, stream(interEncrypted))

            assertThat(decryptor.codec).isEqualTo(VideoCodec.H265)
            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.HEADER_CLEAR)
            assertThat(decryptor.interEncrypted).isEqualTo(interEncrypted)
            assertSameNals(out, expected)
        }

        @Test
        fun `whole-NAL HEVC`() {
            val decryptor = NalDecryptor(KEY)

            val out = run(decryptor, expected.map { encryptBody(it, 0) })

            assertThat(decryptor.codec).isEqualTo(VideoCodec.H265)
            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.WHOLE)
            assertSameNals(out, expected)
        }

        @Test
        fun `type-hint HEVC including short clear duplicate-header NALs`() {
            val decryptor = NalDecryptor(KEY)
            val shortPps = hevc(34, byteArrayOf(0xC1.toByte(), 0x72))
            val units =
                expected.take(3).map { it.copyOf(2) + encryptBody(it, 0) } +
                    listOf(shortPps.copyOf(2) + shortPps) +
                    listOf(expected[3].copyOf(2) + encryptBody(expected[3], 0)) +
                    expected.drop(4)

            val out = run(decryptor, units)

            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.TYPE_HINT)
            assertSameNals(out, expected.take(3) + listOf(shortPps) + expected.drop(3))
        }

        @Test
        fun `clear HEVC stream passes the IRAP gate once`() {
            val decryptor = NalDecryptor(null)
            val units = listOf(trail(1), trail(2)) + expected

            val out = run(decryptor, units)

            assertThat(decryptor.variant).isEqualTo(NalDecryptor.Variant.CLEAR)
            assertThat(decryptor.gatedSlices).isEqualTo(2)
            assertSameNals(out, expected)
        }

        @Test
        fun `only keyframes and no inter slices eventually gives up waiting`() {
            val decryptor = NalDecryptor(KEY)
            val big = hevc(19, ByteArray(1 shl 20) { (it * 3).toByte() })
            val units = stream(false).take(3) + List(6) { headerClear(big, true) }

            val out = run(decryptor, units)

            assertThat(decryptor.interEncrypted).isFalse()
            assertThat(out).hasSize(9)
        }
    }

    @Test
    fun `key derivation pads with NUL and truncates to 16 bytes`() {
        assertThat(NalDecryptor.keyBytes("AB")).isEqualTo(byteArrayOf(0x41, 0x42) + ByteArray(14))
        assertThat(NalDecryptor.keyBytes("0123456789ABCDEFGHIJ")).isEqualTo("0123456789ABCDEF".toByteArray())
        assertThat(NalDecryptor.keyBytes("")).isEqualTo(ByteArray(16))
    }

    @Test
    fun `encrypted bodies are decrypted in place, headers and clear tails untouched`() {
        val decryptor = NalDecryptor(KEY)
        val units = readUnits("h264_nals_header_clear_all.bin")
        val out = run(decryptor, units)
        assertSameNals(out, clearAfterGate)
        // The emitted arrays are the fed arrays themselves (no copy), now holding clear data.
        units.drop(1).zip(out).forEach { (fed, emitted) -> assertThat(emitted).isSameInstanceAs(fed) }
        // A body shorter than one AES block is left alone; a long one keeps its clear tail past 4096 bytes.
        val idrIndex = out.indexOfFirst { it[0].toInt() and 0x1F == 5 }
        val idr = out[idrIndex]
        assertThat(idr.size).isGreaterThan(4096 + 1)
        assertThat(idr.copyOfRange(4097, idr.size)).isEqualTo(clearAfterGate[idrIndex].copyOfRange(4097, idr.size))
    }

    @Test
    fun `tiny NALs never crash detection`() {
        val decryptor = NalDecryptor(KEY)
        repeat(50) { decryptor.feed(NalUnit(byteArrayOf(0x65, 1), 0)) }
        assertThat(decryptor.variant).isNull()
        assertThat(decryptor.keyError).isFalse()
    }

    companion object {
        const val KEY = "ABCDEF1234"

        fun readUnits(name: String): List<ByteArray> {
            val buffer = ByteBuffer.wrap(Fixtures.bytes(name))
            val out = ArrayList<ByteArray>()
            while (buffer.remaining() >= 4) {
                val length = buffer.int
                out += ByteArray(length).also { buffer.get(it) }
            }
            return out
        }

        /** Same as the generator: AES-ECB over whole 16-byte blocks of the first 4096 bytes after [from]. */
        fun encryptBody(
            nal: ByteArray,
            from: Int,
            key: String = KEY,
        ): ByteArray {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(NalDecryptor.keyBytes(key), "AES"))
            val body = nal.copyOfRange(from, nal.size)
            val n = (minOf(body.size, 4096) / 16) * 16
            if (n == 0) return body
            return cipher.doFinal(body, 0, n) + body.copyOfRange(n, body.size)
        }
    }
}
