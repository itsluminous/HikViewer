package com.itsluminous.hikviewer.core.crypto

import java.security.MessageDigest

/**
 * MD5 helpers used by the Hik-Connect protocol.
 *
 * Hik-Connect is not using MD5 for security here but as a fixed encoding the server expects:
 *  * the login form sends `password = MD5(plain)` as lowercase hex,
 *  * a device's `encryptPwd` field equals `MD5(MD5(verificationCode))`, which lets the app verify a
 *    user-typed verification code locally before trying to decrypt a stream with it.
 */
public object Md5 {
    private const val HEX_CHARS = "0123456789abcdef"

    /** Lowercase hex MD5 of [input] encoded as UTF-8. */
    public fun hex(input: String): String = hex(input.toByteArray(Charsets.UTF_8))

    /** Lowercase hex MD5 of raw [bytes]. */
    public fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        val out = CharArray(digest.size * 2)
        digest.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0f]
        }
        return String(out)
    }

    /**
     * `MD5(MD5(input))` as lowercase hex, the form Hik-Connect stores a device's video
     * verification code in (`encryptPwd`). Compare the result with the device status value to
     * check a code the user typed without a network round-trip.
     */
    public fun doubleHex(input: String): String = hex(hex(input))
}
