package com.itsluminous.hikviewer.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.itsluminous.hikviewer.core.stream.StreamQuality

/**
 * User settings: visible cameras and their order, fullscreen quality, tile overlays (names, their
 * position, frame rate), keep-screen-on, the live-camera count and the video verification code,
 * plus one learnt value, each recorder's clock offset. Kept in encrypted preferences because of
 * the verification code. The grid layout and per-layout tile positions live in
 * [GridArrangementStore] on the same preferences.
 *
 * Camera references are [com.itsluminous.hikviewer.core.devices.Camera.key] strings
 * (`serial/channel`), which survive renames on the recorder.
 */
class SettingsStore(
    private val prefs: SharedPreferences,
) {
    /**
     * Ordered keys of the cameras shown in the grid, or null when the user never chose (the grid
     * then shows every camera with a signal, in channel order).
     */
    var visibleCameraKeys: List<String>?
        get() = prefs.getString(KEY_VISIBLE, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }
        set(value) =
            prefs.edit {
                if (value == null) remove(KEY_VISIBLE) else putString(KEY_VISIBLE, value.joinToString(SEPARATOR))
            }

    /** Stream used in fullscreen. SUB is the default: the HEVC main stream needs a hardware decoder. */
    var fullscreenQuality: StreamQuality
        get() =
            prefs.getString(KEY_QUALITY, null)?.let { runCatching { StreamQuality.valueOf(it) }.getOrNull() }
                ?: StreamQuality.SUB
        set(value) = prefs.edit { putString(KEY_QUALITY, value.name) }

    /** Overlay each tile's camera name. Off by default: the recorder already burns the name into the feed. */
    var showCameraNames: Boolean
        get() = prefs.getBoolean(KEY_SHOW_NAMES, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_NAMES, value) }

    /** Which corner the camera name sits in, when shown ([showCameraNames]). Bottom left by default. */
    var cameraNamePosition: NamePosition
        get() = NamePosition.fromName(prefs.getString(KEY_NAME_POSITION, null))
        set(value) = prefs.edit { putString(KEY_NAME_POSITION, value.name) }

    /** Overlay a small rendered-frames-per-second counter on each tile. Off by default. */
    var showFrameRate: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FPS, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_FPS, value) }

    /**
     * Keep the screen on while a HikViewer screen is in the foreground (phones; a TV never dims
     * while video plays and hides the setting). Off by default: a wall of cameras left on a phone
     * would otherwise drain it without the user having asked.
     */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    /** The video verification (encryption) code, once the user entered a matching one. */
    var verificationCode: String?
        get() = prefs.getString(KEY_VERIFICATION_CODE, null)
        set(value) =
            prefs.edit {
                if (value == null) remove(KEY_VERIFICATION_CODE) else putString(KEY_VERIFICATION_CODE, value)
            }

    /**
     * The recorder's clock minus this device's, in minutes, as last learnt for the device with
     * [serial] (see [com.itsluminous.hikviewer.core.playback.RecorderClock]); null until a record
     * search taught it. Cached so a cold open of a camera lands on the recorder's today at once.
     * Learnt, not chosen, so it is not part of [Choices] and never in a backup.
     */
    fun recorderOffsetMinutes(serial: String): Int? {
        val key = KEY_RECORDER_OFFSET_PREFIX + serial
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    /** Remembers [minutes] for [serial]; null forgets it. */
    fun setRecorderOffsetMinutes(
        serial: String,
        minutes: Int?,
    ) = prefs.edit {
        val key = KEY_RECORDER_OFFSET_PREFIX + serial
        if (minutes == null) remove(key) else putInt(key, minutes)
    }

    /** Wipes everything (logout). */
    fun clear() = prefs.edit { clear() }

    /**
     * The user's choices, without the verification code: what a backup carries. The code is a
     * secret for the recorder and never leaves the device in a file.
     */
    data class Choices(
        val visibleCameraKeys: List<String>?,
        val fullscreenQuality: StreamQuality,
        val showCameraNames: Boolean,
        val showFrameRate: Boolean,
        val keepScreenOn: Boolean,
        val cameraNamePosition: NamePosition = NamePosition.DEFAULT,
    )

    /** The current [Choices]. */
    fun choices(): Choices =
        Choices(visibleCameraKeys, fullscreenQuality, showCameraNames, showFrameRate, keepScreenOn, cameraNamePosition)

    /**
     * Replaces every choice in one write (a restore), leaving the verification code as it is.
     * One edit, so a reader never sees half a restore.
     */
    fun applyChoices(choices: Choices) = prefs.edit {
        val visible = choices.visibleCameraKeys
        if (visible == null) remove(KEY_VISIBLE) else putString(KEY_VISIBLE, visible.joinToString(SEPARATOR))
        putString(KEY_QUALITY, choices.fullscreenQuality.name)
        putBoolean(KEY_SHOW_NAMES, choices.showCameraNames)
        putBoolean(KEY_SHOW_FPS, choices.showFrameRate)
        putBoolean(KEY_KEEP_SCREEN_ON, choices.keepScreenOn)
        putString(KEY_NAME_POSITION, choices.cameraNamePosition.name)
    }

    /** Preference keys. */
    companion object {
        private const val SEPARATOR = ","
        private const val KEY_VISIBLE = "visibleCameras"
        private const val KEY_QUALITY = "fullscreenQuality"
        private const val KEY_VERIFICATION_CODE = "verificationCode"
        private const val KEY_SHOW_NAMES = "showCameraNames"
        private const val KEY_SHOW_FPS = "showFrameRate"
        private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        private const val KEY_NAME_POSITION = "cameraNamePosition"
        private const val KEY_RECORDER_OFFSET_PREFIX = "recorderOffset/"
    }
}
