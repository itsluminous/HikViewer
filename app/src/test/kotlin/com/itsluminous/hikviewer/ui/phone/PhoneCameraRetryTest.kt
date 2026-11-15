package com.itsluminous.hikviewer.ui.phone

import android.content.pm.PackageManager
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.stream.StreamException
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.UI_DEADLINE_MILLIS
import com.itsluminous.hikviewer.ui.UI_POLL_MILLIS
import com.itsluminous.hikviewer.ui.grid.CameraTileView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The phone camera screen's retry icon, which works although the picture is not clickable. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-port-xhdpi")
class PhoneCameraRetryTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud =
        FakeCloudServices(cameraCount = 4, signalCount = 3).apply {
            playbackPaced = false
            playbackHold = true
            playbackFramesPerWindow = 24
        }
    private val account get() = cloud.accounts.single()

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        // Longer than GestureDetector's 300 ms tap confirmation, so a hide never races a tap in the tests below.
        app.graph = TestGraph.create(app, cloud, topBarAutoHideMillis = 5_000)
        kotlinx.coroutines.runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + UI_DEADLINE_MILLIS
        while (!condition() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(UI_POLL_MILLIS)
        }
        ShadowLooper.idleMainLooper()
        assertThat(condition()).isTrue()
    }

    private fun PhoneCameraActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `the retry icon reopens a failed live picture, though the picture itself is not clickable`() {
        val failing = Camera(FakeCloudServices.SERIAL, 2, "Camera 2", hasSignal = true, isOnline = true)
        account.nextStreamErrorFor[failing.key] = StreamException.ChannelOffline(failing)
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 1)).use { scenario ->
            var failed = false
            awaitUi {
                scenario.onActivity { a -> failed = a.findViewById<CameraTileView>(R.id.video).showsRetry }
                failed
            }
            scenario.onActivity { a ->
                assertThat(a.findViewById<CameraTileView>(R.id.video).isClickable).isFalse()
                assertThat(account.opened).hasSize(1)
                a.findViewById<View>(R.id.retry).performClick()
            }
            var recovered = false
            awaitUi {
                scenario.onActivity { a -> recovered = !a.findViewById<CameraTileView>(R.id.video).showsRetry }
                recovered
            }
            assertThat(account.opened).hasSize(2)
        }
    }
}
