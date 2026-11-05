package com.itsluminous.hikviewer.demo

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.edit
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.data.SessionStore
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.player.DeviceCapabilities
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenActivity
import com.itsluminous.hikviewer.ui.grid.GridActivity
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import com.itsluminous.hikviewer.ui.login.LoginActivity
import com.itsluminous.hikviewer.ui.phone.PhoneCameraActivity
import com.itsluminous.hikviewer.ui.playback.PlaybackActivity
import com.itsluminous.hikviewer.ui.settings.SettingsActivity
import kotlinx.coroutines.runBlocking

/**
 * Debug-only demo mode: replaces the app's dependency graph with one whose cloud is
 * [DemoCloudServices] (synthetic test patterns, placeholder camera names, no network) and opens
 * the requested screen. Used for the README screenshots and for trying the UI without an account:
 *
 * ```
 * adb shell am start -n com.itsluminous.hikviewer.debug/com.itsluminous.hikviewer.demo.DemoActivity \
 *     --es screen grid --es layout GRID_4X3 --ei cameras 12
 * ```
 *
 * Extras: `screen` = `login` | `grid` (default) | `fullscreen` | `camera` (the phone's portrait
 * screen) | `playback` | `settings`; `layout` = a [GridLayoutMode] name (default AUTO);
 * `cameras` = channel count (default 12); `index` = tile to open for `fullscreen`/`camera`;
 * `names` = overlay the camera names on the tiles (default true, so a reader can tell tiles apart;
 * the app's own default is off).
 *
 * The demo keeps its own preference files, so it never reads or writes a real session, and the
 * swapped graph lives only until the process dies; the next normal launch is unaffected. The
 * Activity has no UI of its own: it rewires the graph, launches the target in a fresh task and
 * finishes.
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra(EXTRA_SCREEN) ?: SCREEN_GRID
        val layout = GridLayoutMode.fromStoredName(intent.getStringExtra(EXTRA_LAYOUT)) ?: GridLayoutMode.AUTO
        val cameras = intent.getIntExtra(EXTRA_CAMERAS, DemoCloudServices.DEFAULT_CAMERA_COUNT).coerceAtLeast(1)
        val index = intent.getIntExtra(EXTRA_INDEX, 0)

        val graph = buildGraph(loggedIn = screen != SCREEN_LOGIN, cameras = cameras)
        // The single-camera screens read the inventory the grid normally loaded before them; the
        // demo cloud answers from memory, so loading it here costs nothing and blocks nobody.
        if (graph.account.isLoggedIn) runBlocking { graph.account.loadInventory() }
        graph.arrangementStore.lastLayout = layout
        graph.settingsStore.showCameraNames = intent.getBooleanExtra(EXTRA_NAMES, true)
        (application as HikViewerApp).graph = graph

        val target =
            when (screen) {
                SCREEN_LOGIN -> Intent(this, LoginActivity::class.java)
                SCREEN_FULLSCREEN -> FullscreenActivity.intent(this, index)
                SCREEN_CAMERA -> PhoneCameraActivity.intent(this, index)
                SCREEN_PLAYBACK -> PlaybackActivity.intent(this, cameraKey(index))
                SCREEN_SETTINGS -> Intent(this, SettingsActivity::class.java)
                else -> Intent(this, GridActivity::class.java)
            }
        // A fresh task drops any screen still bound to the previous graph.
        startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun buildGraph(
        loggedIn: Boolean,
        cameras: Int,
    ): AppGraph {
        val sessionPrefs = getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).also { it.edit { clear() } }
        val settingsPrefs = getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).also { it.edit { clear() } }
        if (loggedIn) SessionStore(sessionPrefs).save(DemoCloudServices.DEMO_SESSION)
        return AppGraph(
            sessionPrefs = sessionPrefs,
            settingsPrefs = settingsPrefs,
            cloud = DemoCloudServices(DemoClips(assets), cameras),
            capabilities = DeviceCapabilities.of(getSystemService(ActivityManager::class.java)),
        )
    }

    private fun cameraKey(index: Int): String = "${DemoCloudServices.SERIAL}/${index + 1}"

    /** Intent extras. */
    companion object {
        const val EXTRA_SCREEN: String = "screen"
        const val EXTRA_LAYOUT: String = "layout"
        const val EXTRA_CAMERAS: String = "cameras"
        const val EXTRA_INDEX: String = "index"
        const val EXTRA_NAMES: String = "names"

        const val SCREEN_LOGIN: String = "login"
        const val SCREEN_GRID: String = "grid"
        const val SCREEN_FULLSCREEN: String = "fullscreen"
        const val SCREEN_CAMERA: String = "camera"
        const val SCREEN_PLAYBACK: String = "playback"
        const val SCREEN_SETTINGS: String = "settings"

        private const val SESSION_PREFS = "demo-session"
        private const val SETTINGS_PREFS = "demo-settings"
    }
}
