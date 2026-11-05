package com.itsluminous.hikviewer.di

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.data.AccountRepository
import com.itsluminous.hikviewer.data.CloudServices
import com.itsluminous.hikviewer.data.GridArrangementStore
import com.itsluminous.hikviewer.data.HikConnectCloudServices
import com.itsluminous.hikviewer.data.SessionStore
import com.itsluminous.hikviewer.data.SettingsStore
import com.itsluminous.hikviewer.player.DeviceCapabilities
import com.itsluminous.hikviewer.player.DeviceHeadroomProbe
import com.itsluminous.hikviewer.player.LiveBudget
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.player.StreamThreads

/**
 * Hand-wired dependency graph (no DI framework: four screens and a dozen classes do not justify
 * the build-time cost of Hilt on a project meant to stay readable).
 *
 * Everything that touches the network or the key store is reachable through an interface so the
 * same graph can be built with fakes in tests: [CloudServices] for the Hik-Connect cloud,
 * plain [SharedPreferences] instead of the encrypted ones under Robolectric.
 */
class AppGraph(
    /** Where the session and feature code live; encrypted at rest in production. */
    sessionPrefs: SharedPreferences,
    /** Where user settings live; encrypted too because they include the verification code. */
    settingsPrefs: SharedPreferences,
    /** Cloud access; the real client or a fake. */
    val cloud: CloudServices,
    /** What this device can decode comfortably; drives [LiveBudget]. */
    val capabilities: DeviceCapabilities,
    /** How long the grid's top bar stays visible after the last key press. Tests shorten it. */
    val topBarAutoHideMillis: Long = DEFAULT_TOP_BAR_AUTO_HIDE_MILLIS,
    /** Builds the `MediaCodec` wrappers; tests substitute a recorder so no codec is ever opened. */
    val playbackFactory: PlaybackFactory = PlaybackFactory(),
) {
    /** Persisted session and per-install feature code. */
    val sessionStore: SessionStore = SessionStore(sessionPrefs)

    /** Persisted user settings. */
    val settingsStore: SettingsStore = SettingsStore(settingsPrefs)

    /** Last layout and per-layout tile positions; on the settings preferences so logout clears it. */
    val arrangementStore: GridArrangementStore = GridArrangementStore(settingsPrefs)

    /** Login state, device and camera cache, stream access. One per process. */
    val account: AccountRepository = AccountRepository(cloud, sessionStore, settingsStore)

    /** Factories and defaults. */
    companion object {
        /** The brief's requirement: hide 5 s after the last key press. */
        const val DEFAULT_TOP_BAR_AUTO_HIDE_MILLIS: Long = 5_000

        private const val SESSION_PREFS = "session.enc"
        private const val SETTINGS_PREFS = "settings.enc"

        /** The graph of the running app. */
        fun of(context: Context): AppGraph = (context.applicationContext as HikViewerApp).graph

        /** Production wiring: encrypted preferences and the real Hik-Connect client. */
        fun production(context: Context): AppGraph {
            val sessionPrefs = encryptedPrefs(context, SESSION_PREFS)
            val settingsPrefs = encryptedPrefs(context, SETTINGS_PREFS)
            val sessionStore = SessionStore(sessionPrefs)
            val activityManager = context.getSystemService(ActivityManager::class.java)
            return AppGraph(
                sessionPrefs = sessionPrefs,
                settingsPrefs = settingsPrefs,
                cloud = HikConnectCloudServices({ sessionStore.featureCode() }, StreamThreads.dispatcher),
                capabilities = DeviceCapabilities.of(activityManager),
                playbackFactory = PlaybackFactory(headroomFactory = { DeviceHeadroomProbe(activityManager) }),
            )
        }

        private fun encryptedPrefs(
            context: Context,
            name: String,
        ): SharedPreferences {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
