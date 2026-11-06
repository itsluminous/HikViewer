package com.itsluminous.hikviewer.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.itsluminous.hikviewer.core.stream.StreamQuality

/**
 * User settings: visible cameras and their order, fullscreen quality, tile overlays, keep-screen-on,
 * the live-camera count and the video verification code. Kept in encrypted preferences because of
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

    /**
     * How many grid tiles decode live before the rest fall back to one keyframe a second, or null
     * for Automatic (the device-derived ceiling, with probing above it). An explicit value is one
     * of [LIVE_CAMERA_CHOICES]; anything else stored (an old build, a hand edit) reads as Automatic
     * rather than as a ceiling the device was never measured for.
     */
    var liveCameras: Int?
        get() = prefs.getInt(KEY_LIVE_CAMERAS, LIVE_CAMERAS_AUTOMATIC).takeIf { it in LIVE_CAMERA_CHOICES }
        set(value) =
            prefs.edit {
                if (value == null) remove(KEY_LIVE_CAMERAS) else putInt(KEY_LIVE_CAMERAS, value)
            }

    /** The video verification (encryption) code, once the user entered a matching one. */
    var verificationCode: String?
        get() = prefs.getString(KEY_VERIFICATION_CODE, null)
        set(value) =
            prefs.edit {
                if (value == null) remove(KEY_VERIFICATION_CODE) else putString(KEY_VERIFICATION_CODE, value)
            }

    /** Wipes everything (logout). */
    fun clear() = prefs.edit { clear() }

    /** The explicit live-camera counts the Settings chooser offers; null in [liveCameras] is Automatic. */
    companion object {
        /** Even steps from the smallest grid that needs a cap to the largest layout (4 x 4). */
        val LIVE_CAMERA_CHOICES: List<Int> = listOf(4, 6, 8, 10, 12, 14, 16)

        private const val LIVE_CAMERAS_AUTOMATIC = 0
        private const val SEPARATOR = ","
        private const val KEY_VISIBLE = "visibleCameras"
        private const val KEY_QUALITY = "fullscreenQuality"
        private const val KEY_VERIFICATION_CODE = "verificationCode"
        private const val KEY_SHOW_NAMES = "showCameraNames"
        private const val KEY_SHOW_FPS = "showFrameRate"
        private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        private const val KEY_LIVE_CAMERAS = "liveCameras"
    }
}
