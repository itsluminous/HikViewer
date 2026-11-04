package com.itsluminous.hikviewer.ui.login

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.Region
import org.junit.Test

class AccountInputTest {
    @Test
    fun `bare number gets the region's code, spaces and dashes removed`() {
        assertThat(AccountInput.resolve(" 98765-43210 ", Region.INDIA)).isEqualTo("919876543210")
        assertThat(AccountInput.resolve("2015550123", Region.USA)).isEqualTo("12015550123")
        assertThat(AccountInput.resolve("1234 5678", Region.SINGAPORE)).isEqualTo("6512345678")
    }

    @Test
    fun `global region has no code so the number is sent as typed`() {
        assertThat(Region.GLOBAL.telephoneCode).isNull()
        assertThat(AccountInput.resolve("911234567890", Region.GLOBAL)).isEqualTo("911234567890")
    }

    @Test
    fun `a leading plus means the user typed the code and the plus itself is dropped`() {
        assertThat(AccountInput.resolve("+91 98765 43210", Region.USA)).isEqualTo("919876543210")
        assertThat(AccountInput.resolve("+", Region.INDIA)).isNull()
    }

    @Test
    fun `emails go through untouched`() {
        assertThat(AccountInput.resolve(" Me@Example.com ", Region.INDIA)).isEqualTo("Me@Example.com")
    }

    @Test
    fun `blank input is nothing`() {
        assertThat(AccountInput.resolve("   ", Region.INDIA)).isNull()
    }

    @Test
    fun `every region but Global carries a dialling code`() {
        Region.entries.filter { it != Region.GLOBAL }.forEach { r ->
            assertThat(r.telephoneCode).isNotNull()
            assertThat(r.telephoneCode!!.all(Char::isDigit)).isTrue()
        }
    }
}
