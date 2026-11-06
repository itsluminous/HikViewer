package com.itsluminous.hikviewer.ui.common

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import com.itsluminous.hikviewer.di.AppGraph

/**
 * Applies the "Keep screen on" setting to every HikViewer window. Registered once by the
 * Application, it sets or clears `FLAG_KEEP_SCREEN_ON` on each Activity as it starts, so the
 * setting holds across the grid, the camera screens and Settings without each of them knowing
 * about it; Settings calls [apply] itself when the switch is flipped, so the change takes effect
 * at once on the screen the user is looking at. The flag is a request to the window manager, not
 * a wake lock: it costs nothing once the Activity is stopped, which is why there is nothing to
 * undo in `onStop`.
 */
object KeepScreenOn : Application.ActivityLifecycleCallbacks {
    /** Sets or clears the window flag from the current setting. */
    fun apply(activity: Activity) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (AppGraph.of(activity).settingsStore.keepScreenOn) {
            activity.window.addFlags(flag)
        } else {
            activity.window.clearFlags(flag)
        }
    }

    override fun onActivityStarted(activity: Activity) = apply(activity)

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
