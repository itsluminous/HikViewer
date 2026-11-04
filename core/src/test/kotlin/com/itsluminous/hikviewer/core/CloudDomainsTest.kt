package com.itsluminous.hikviewer.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CloudDomainsTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "api.hik-connect.com", "apiiindia.hik-connect.com", "indiaauth.ezvizlife.com",
            "yducvtm.ezvizlife.com", "apiirus.hik-connectru.com", "a.b.c.hikvision.com", "x.ezviz.com",
        ],
    )
    fun `hikvision and ezviz hosts are allowed`(host: String) {
        assertThat(CloudDomains.isAllowed(host)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "hik-connect.com", "evil.example.com", "hik-connect.com.evil.net", "api.hik-connect.com/",
            "api.hik_connect.com", "", "api.hik-connect.com.", "ezvizlife.com",
        ],
    )
    fun `everything else is refused`(host: String) {
        assertThat(CloudDomains.isAllowed(host)).isFalse()
    }

    @Test
    fun `requireAllowed normalises scheme case and trailing slash`() {
        assertThat(
            CloudDomains.requireAllowed("HTTPS://ApiIIndia.Hik-Connect.com/"),
        ).isEqualTo("apiiindia.hik-connect.com")
        assertThat(CloudDomains.requireAllowed(" http://a.ezvizlife.com ")).isEqualTo("a.ezvizlife.com")
    }

    @Test
    fun `requireAllowed throws for foreign hosts`() {
        val error = assertThrows<UnsafeDomainException> { CloudDomains.requireAllowed("https://evil.example.com") }
        assertThat(error.message).contains("evil.example.com")
    }
}
