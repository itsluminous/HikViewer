package com.itsluminous.hikviewer.core.devices

import com.itsluminous.hikviewer.core.crypto.Md5

/** A Hik-Connect device (DVR/NVR/camera) as listed in the account. */
public data class Device(
    /** Cloud serial; the key for every other API call. */
    val serial: String,
    /** User-visible device name. */
    val name: String,
    /** Model string (`customType`, e.g. `DS-7A16HQHI-K1`) or the generic `deviceType`. */
    val model: String?,
    /** Device-level online state (`statusInfos.globalStatus == 1`); null when the cloud did not say. */
    val isOnline: Boolean?,
    /** True when streams are encrypted with the video verification code. */
    val isEncrypted: Boolean,
    /** `MD5(MD5(verificationCode))` hex; present only for encrypted devices. */
    val encryptPwd: String?,
    /** Number of channels the cloud lists for the device (placeholders included). */
    val channelCount: Int,
)

/** One channel of a device. The grid shows one tile per camera. */
public data class Camera(
    /** Serial of the owning device. */
    val deviceSerial: String,
    /** 1-based channel number used in stream URLs. */
    val channelNo: Int,
    /** Channel name as configured on the recorder. */
    val name: String,
    /** True when the recorder reports a video signal on this input. */
    val hasSignal: Boolean,
    /** True when the owning device is online; a camera cannot be online on an offline recorder. */
    val isOnline: Boolean,
) {
    /** Stable key for preferences (visible set, order). */
    val key: String get() = "$deviceSerial/$channelNo"
}

/** Local check of a user-typed video verification code against a device's `encryptPwd`. */
public object VerificationCode {
    /**
     * True when `MD5(MD5(input))` equals [encryptPwd] (case-insensitive hex). Lets the UI reject a
     * typo before a stream is opened with the wrong key. Null [encryptPwd] cannot be verified and
     * returns false.
     */
    public fun matches(
        input: String,
        encryptPwd: String?,
    ): Boolean {
        if (encryptPwd.isNullOrBlank() || input.isEmpty()) return false
        return Md5.doubleHex(input).equals(encryptPwd.trim(), ignoreCase = true)
    }
}
