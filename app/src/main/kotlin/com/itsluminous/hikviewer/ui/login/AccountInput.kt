package com.itsluminous.hikviewer.ui.login

import com.itsluminous.hikviewer.core.auth.Region

/**
 * Turns what the user typed in the account field into the `account` Hik-Connect expects.
 *
 * A Hik-Connect phone account is `telephoneCode + number` with no `+` (teardown, `login/v6`).
 * The form asks for the bare mobile number and the chosen [Region] supplies the code, so a user
 * in India types `98765 43210` and `919876543210` is sent. Three inputs bypass the prefix and go
 * through as typed: an email (contains `@`), a number the user prefixed with `+` (they typed the
 * country code themselves; the `+` itself is dropped because the server wants digits), and any
 * number when the region has no [Region.telephoneCode] (Global). Spaces and dashes inside a
 * number are removed; everything else is left alone so an unusual account is never mangled.
 */
object AccountInput {
    /** The account string to send for [raw] under [region], or null when nothing was typed. */
    fun resolve(
        raw: String,
        region: Region,
    ): String? {
        val typed = raw.trim()
        if (typed.isEmpty()) return null
        if ('@' in typed) return typed
        val digits = typed.filterNot { it == ' ' || it == '-' }
        if (digits.startsWith('+')) return digits.drop(1).ifEmpty { null }
        val code = region.telephoneCode ?: return digits
        return code + digits
    }
}
