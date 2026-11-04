package com.itsluminous.hikviewer.core.devices

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.crypto.Md5
import org.junit.jupiter.api.Test

class VerificationCodeTest {
    @Test
    fun `matches when encryptPwd is the double md5 of the input`() {
        val stored = Md5.doubleHex("ABCDEF")

        assertThat(VerificationCode.matches("ABCDEF", stored)).isTrue()
        assertThat(VerificationCode.matches("ABCDEF", stored.uppercase())).isTrue()
        assertThat(VerificationCode.matches("ABCDEF", " $stored ")).isTrue()
    }

    @Test
    fun `rejects wrong code empty input and missing hash`() {
        val stored = Md5.doubleHex("ABCDEF")

        assertThat(VerificationCode.matches("abcdef", stored)).isFalse()
        assertThat(VerificationCode.matches("", stored)).isFalse()
        assertThat(VerificationCode.matches("ABCDEF", null)).isFalse()
        assertThat(VerificationCode.matches("ABCDEF", "")).isFalse()
    }

    @Test
    fun `known answer`() {
        // md5("ABCDEF") = 8827a41122a5028b9808c7bf84b9fcf6; md5 of that hex string:
        assertThat(VerificationCode.matches("ABCDEF", "c69b2f6b0d6e2b7a0a1e9c4b9f5c3d2e")).isFalse()
        assertThat(Md5.doubleHex("ABCDEF")).isEqualTo(Md5.hex("8827a41122a5028b9808c7bf84b9fcf6"))
    }
}
