package com.itsluminous.hikviewer.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class Md5Test {
    @ParameterizedTest(name = "md5(\"{0}\") = {1}")
    @CsvSource(
        // RFC 1321 test suite vectors.
        "'', d41d8cd98f00b204e9800998ecf8427e",
        "a, 0cc175b9c0f1b6a831c399e269772661",
        "abc, 900150983cd24fb0d6963f7d28e17f72",
        "message digest, f96b697d7cb7938d525a2f31aaf161d0",
        "abcdefghijklmnopqrstuvwxyz, c3fcd3d76192e4007dfb496cca67e13b",
        "12345678901234567890123456789012345678901234567890123456789012345678901234567890, 57edf4a22be3c955ac49da2e2107b67a",
    )
    fun `hex matches RFC 1321 vectors`(input: String, expected: String) {
        assertThat(Md5.hex(input)).isEqualTo(expected)
    }

    @Test
    fun `hex encodes UTF-8 before hashing`() {
        // "é" is 0xC3 0xA9 in UTF-8; hashing the bytes directly must give the same digest.
        assertThat(Md5.hex("é")).isEqualTo(Md5.hex(byteArrayOf(0xC3.toByte(), 0xA9.toByte())))
    }

    @Test
    fun `hex is always 32 lowercase characters`() {
        val digest = Md5.hex("HikViewer")
        assertThat(digest).hasLength(32)
        assertThat(digest).matches("[0-9a-f]{32}")
    }

    @Test
    fun `doubleHex hashes the hex string of the first digest`() {
        // md5("abc") = 900150983cd24fb0d6963f7d28e17f72; md5 of that ASCII string:
        assertThat(Md5.doubleHex("abc")).isEqualTo(Md5.hex("900150983cd24fb0d6963f7d28e17f72"))
        assertThat(Md5.doubleHex("abc")).isNotEqualTo(Md5.hex("abc"))
    }
}
