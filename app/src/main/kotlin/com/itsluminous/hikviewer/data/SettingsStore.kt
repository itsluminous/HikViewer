package com.itsluminous.hikviewer.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.itsluminous.hikviewer.core.stream.StreamQuality

/**
 * User settings: visible cameras and their order, fullscreen quality, tile overlays and the video
 * verification code. Kept in encrypted preferences because of the verification code. The grid
 * layout and per-layout tile positions live in [GridArrangementStore] on the same preferences.
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

    /** Overlay a small rendered-frames-per-second counter on each tile. Off by default. */
    var showFrameRate: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FPS, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_FPS, value) }

    /** The video verification (encryption) code, once the user entered a matching one. */
    var verificationCode: String?
        get() = prefs.getString(KEY_VERIFICATION_CODE, null)
        set(value) =
            prefs.edit {
                if (value == null) remove(KEY_VERIFICATION_CODE) else putString(KEY_VERIFICATION_CODE, value)
            }

    /** Wipes everything (logout). */
    fun clear() = prefs.edit { clear() }

    private companion object {
        const val SEPARATOR = ","
        const val KEY_VISIBLE = "visibleCameras"
        const val KEY_QUALITY = "fullscreenQuality"
        const val KEY_VERIFICATION_CODE = "verificationCode"
        const val KEY_SHOW_NAMES = "showCameraNames"
        const val KEY_SHOW_FPS = "showFrameRate"
    }
}
