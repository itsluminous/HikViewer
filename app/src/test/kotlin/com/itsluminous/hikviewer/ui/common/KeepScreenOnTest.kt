package com.itsluminous.hikviewer.ui.common

import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.grid.GridActivity
import com.itsluminous.hikviewer.ui.phone.PhoneCameraActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * The setting reaches every screen's window through the Application's lifecycle callback: no
 * Activity sets the flag itself, and a toggle made in Settings is picked up when the grid starts
 * again (the Activity behind Settings goes through onStart on the way back).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-port-xhdpi")
class KeepScreenOnTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        app.graph = TestGraph.create(app, FakeCloudServices(cameraCount = 4, signalCount = 3))
        kotlinx.coroutines.runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
    }

    private fun Activity.keepsScreenOn(): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun `the grid's window keeps the screen on only while the setting is on`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a -> assertThat(a.keepsScreenOn()).isFalse() }
            // Toggled in Settings; the grid sees it when it comes back to the foreground.
            app.graph.settingsStore.keepScreenOn = true
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a -> assertThat(a.keepsScreenOn()).isTrue() }
            app.graph.settingsStore.keepScreenOn = false
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a -> assertThat(a.keepsScreenOn()).isFalse() }
        }
    }

    @Test
    fun `the camera screen's window carries the flag from its first start`() {
        app.graph.settingsStore.keepScreenOn = true
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a -> assertThat(a.keepsScreenOn()).isTrue() }
        }
    }
}
