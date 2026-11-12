package com.itsluminous.hikviewer.ui.phone

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * The phone's auto-rotate switch (quick settings "Auto-rotate"), read from
 * `Settings.System.ACCELEROMETER_ROTATION` (1 = on, the default when the row is absent). With it
 * on, the phone turns the screen itself and [PhoneCameraActivity] has nothing to add; with it off
 * the screen offers a fullscreen button that forces the orientation instead. [watch] reports every
 * change of the row on the main thread, so flipping the switch while the screen is open updates
 * the buttons and releases a forced orientation at once. Each [watch] must be paired with [stop].
 */
class AutoRotateSetting(
    private val resolver: ContentResolver,
) {
    /** True while the phone rotates the screen by itself. */
    val isOn: Boolean
        get() = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, ON) == ON

    private var observer: ContentObserver? = null

    /** Calls [onChange] on the main thread whenever the row changes; no-op while already watching. */
    fun watch(onChange: () -> Unit) {
        if (observer != null) return
        val watcher =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    onChange()
                }
            }
        resolver.registerContentObserver(uri, false, watcher)
        observer = watcher
    }

    /** Stops the [watch]; safe to call without one. */
    fun stop() {
        observer?.let(resolver::unregisterContentObserver)
        observer = null
    }

    private companion object {
        /** The value of `ACCELEROMETER_ROTATION` for "on", and the default when the row is missing. */
        const val ON = 1
        val uri = Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)
    }
}
