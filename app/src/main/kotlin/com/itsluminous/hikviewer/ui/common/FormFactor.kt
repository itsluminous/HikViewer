package com.itsluminous.hikviewer.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Television or hand-held? The two get different chrome: a TV has a D-pad and no touch, so bars
 * hide and come back on any key; a phone has no D-pad, so a hidden bar would be unreachable and
 * the bar stays on screen, and tapping a tile opens the portrait live + timeline screen instead
 * of the landscape fullscreen one.
 */
object FormFactor {
    /**
     * True when [context] runs on a television: the leanback feature is declared, the UI mode is
     * TV (system service or current configuration), or the device has no touch screen at all.
     */
    fun isTelevision(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        val uiMode = context.getSystemService(UiModeManager::class.java)
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val configuredMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (configuredMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
        return !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
