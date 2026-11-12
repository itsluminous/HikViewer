package com.itsluminous.hikviewer.ui.phone

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.UI_DEADLINE_MILLIS
import com.itsluminous.hikviewer.ui.UI_POLL_MILLIS
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

private const val PORTRAIT = "w360dp-h640dp-port-xhdpi"
private const val LANDSCAPE = "w640dp-h360dp-land-xhdpi"

/**
 * The phone camera screen's fullscreen buttons, which stand in for turning a phone whose
 * auto-rotate is off: `Settings.System.ACCELEROMETER_ROTATION` is the shadowed system row, and
 * Robolectric's `putInt` notifies the screen's observer like the real settings provider does.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PORTRAIT)
class PhoneCameraOrientationLockTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud = FakeCloudServices(cameraCount = 4, signalCount = 3).apply { playbackPaced = false }
    private val account get() = cloud.accounts.single()

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        app.graph = TestGraph.create(app, cloud, topBarAutoHideMillis = IDLE_MILLIS)
        kotlinx.coroutines.runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
    }

    private fun autoRotate(on: Boolean) {
        Settings.System.putInt(app.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
        ShadowLooper.idleMainLooper()
    }

    private fun awaitStream() {
        val deadline = System.currentTimeMillis() + UI_DEADLINE_MILLIS
        while (account.opened.isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(UI_POLL_MILLIS)
        }
        ShadowLooper.idleMainLooper()
        assertThat(account.opened).isNotEmpty()
    }

    private fun PhoneCameraActivity.enterButton(): View? = findViewById(R.id.fullscreen_button)

    private fun PhoneCameraActivity.exitButton(): View? = findViewById(R.id.fullscreen_exit_button)

    private fun PhoneCameraActivity.pressBack() {
        onBackPressedDispatcher.onBackPressed()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `upright with auto-rotate off the fullscreen button shows and forces sensor landscape`() {
        autoRotate(false)
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitStream()
            scenario.onActivity { a ->
                assertThat(a.isUpright).isTrue()
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                val button = a.enterButton()!!
                assertThat(button.visibility).isEqualTo(View.VISIBLE)
                assertThat(button.contentDescription.toString()).isEqualTo("Fullscreen")
                assertThat((button.parent as View).id).isEqualTo(R.id.video_frame) // in the picture's corner
                assertThat(a.exitButton()).isNull() // the exit icon belongs to the sideways overlay
                button.performClick()
                ShadowLooper.idleMainLooper()
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                assertThat(a.isFinishing).isFalse()
            }
        }
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `sideways with auto-rotate off the exit button sits in the controls and forces portrait`() {
        autoRotate(false)
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitStream()
            scenario.onActivity { a ->
                assertThat(a.isUpright).isFalse()
                val button = a.exitButton()!!
                assertThat(button.visibility).isEqualTo(View.VISIBLE)
                assertThat(button.contentDescription.toString()).isEqualTo("Exit fullscreen")
                assertThat(isInside(button, a.findViewById(R.id.controls))).isTrue()
                assertThat(a.enterButton()).isNull()
                // It shares the overlay's auto-hide: the overlay goes, and a tap brings both back.
                ShadowLooper.idleMainLooper(IDLE_MILLIS + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(a.controlsShown).isFalse()
                assertThat(button.isShown).isFalse()
                button.performClick()
                ShadowLooper.idleMainLooper()
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                // Not forced sideways by this screen, so Back leaves as it always did.
                a.pressBack()
                assertThat(a.isFinishing).isTrue()
            }
        }
    }

    /**
     * Entering fullscreen recreates the screen sideways (here: the qualifier swap plus `recreate()`);
     * the saved state carries the lock into the new instance, where one Back goes upright (another
     * recreate, lock now portrait) and only the next Back leaves.
     */
    @Test
    fun `the lock survives the recreate and Back exits fullscreen before it leaves`() {
        autoRotate(false)
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 1)).use { scenario ->
            awaitStream()
            scenario.onActivity { a -> a.enterButton()!!.performClick() }
            ShadowLooper.idleMainLooper()

            RuntimeEnvironment.setQualifiers(LANDSCAPE)
            scenario.recreate()
            scenario.onActivity { a ->
                assertThat(a.isUpright).isFalse()
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                assertThat(a.exitButton()!!.visibility).isEqualTo(View.VISIBLE)
                a.pressBack()
                assertThat(a.isFinishing).isFalse()
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            }

            RuntimeEnvironment.setQualifiers(PORTRAIT)
            scenario.recreate()
            scenario.onActivity { a ->
                assertThat(a.isUpright).isTrue()
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                assertThat(a.enterButton()!!.visibility).isEqualTo(View.VISIBLE)
                assertThat(a.viewModelIndex()).isEqualTo(1) // the camera rode along as on a physical turn
                a.pressBack()
                assertThat(a.isFinishing).isTrue()
            }
        }
    }

    @Test
    fun `with auto-rotate on there is no button upright and nothing is forced`() {
        autoRotate(true)
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitStream()
            scenario.onActivity { a ->
                assertThat(a.enterButton()!!.visibility).isEqualTo(View.GONE)
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            }
        }
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `with auto-rotate on there is no button sideways and Back leaves at once`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitStream()
            scenario.onActivity { a ->
                assertThat(a.exitButton()!!.visibility).isEqualTo(View.GONE)
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                a.pressBack()
                assertThat(a.isFinishing).isTrue()
            }
        }
    }

    /** Quick settings flipped while the screen is up: the observer hides the button and releases the lock. */
    @Test
    fun `turning auto-rotate on while forced releases the lock and hides the button, off brings it back`() {
        autoRotate(false)
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitStream()
            scenario.onActivity { a ->
                a.enterButton()!!.performClick()
                ShadowLooper.idleMainLooper()
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
            }
            autoRotate(true)
            scenario.onActivity { a ->
                assertThat(a.enterButton()!!.visibility).isEqualTo(View.GONE)
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            }
            autoRotate(false)
            scenario.onActivity { a ->
                assertThat(a.enterButton()!!.visibility).isEqualTo(View.VISIBLE)
                // The released lock is not reinstated: the user asks for fullscreen again if they want it.
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            }
        }
    }

    /** A paused screen stops watching; resuming re-reads the switch. */
    @Test
    fun `a change made while paused is picked up on resume`() {
        autoRotate(false)
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitStream()
            scenario.moveToState(Lifecycle.State.STARTED)
            autoRotate(true)
            scenario.onActivity { a -> assertThat(a.enterButton()!!.visibility).isEqualTo(View.VISIBLE) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { a -> assertThat(a.enterButton()!!.visibility).isEqualTo(View.GONE) }
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun `a television shows no button and locks nothing even with the row at zero`() {
        autoRotate(false)
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitStream()
            scenario.onActivity { a ->
                assertThat(a.exitButton()!!.visibility).isEqualTo(View.GONE)
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                a.pressBack()
                assertThat(a.isFinishing).isTrue()
            }
        }
    }

    private fun PhoneCameraActivity.viewModelIndex(): Int = viewModels<PhoneCameraViewModel>().value.index.value

    private fun isInside(
        view: View,
        ancestor: ViewGroup,
    ): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    private companion object {
        /** The graph's idle time before the landscape controls hide (5 s in production). */
        const val IDLE_MILLIS = 1_000L
    }
}
