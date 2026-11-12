package com.itsluminous.hikviewer.ui.fullscreen

import android.app.Activity
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.data.NamePosition
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.TileMode
import com.itsluminous.hikviewer.ui.grid.CameraTileView
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class FullscreenActivityTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud = FakeCloudServices(cameraCount = 4, signalCount = 3)

    @Before
    fun setUp() {
        app.graph = TestGraph.create(app, cloud)
        kotlinx.coroutines.runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
    }

    @Test
    fun `shows the requested camera on a hardware decoder and returns its index`() {
        ActivityScenario.launch<FullscreenActivity>(FullscreenActivity.intent(app, 1)).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a ->
                assertThat(a.findViewById<TextView>(R.id.name).text.toString()).isEqualTo("Camera 2")
                val vm = androidx.lifecycle.ViewModelProvider(a)[FullscreenViewModel::class.java]
                assertThat(
                    vm.playback.players.value
                        .single()
                        .mode,
                ).isEqualTo(TileMode.HW_LIVE)
                assertThat(
                    cloud.accounts
                        .single()
                        .opened
                        .single(),
                ).isEqualTo(vm.camera to StreamQuality.SUB)
                a.finish()
                assertThat(a.isFinishing).isTrue()
                assertThat(shadowOf(a).resultCode).isEqualTo(Activity.RESULT_OK)
                assertThat(shadowOf(a).resultIntent.getIntExtra(FullscreenActivity.EXTRA_INDEX, -1)).isEqualTo(1)
            }
        }
    }

    @Test
    fun `left and right switch camera with wrap-around`() {
        ActivityScenario.launch<FullscreenActivity>(FullscreenActivity.intent(app, 0)).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a ->
                a.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                ShadowLooper.idleMainLooper()
                assertThat(a.findViewById<TextView>(R.id.name).text.toString()).isEqualTo("Camera 3")
                a.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                a.findViewById<View>(R.id.right_zone).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(a.findViewById<TextView>(R.id.name).text.toString()).isEqualTo("Camera 2")
                // Streams start on worker threads: a camera switched away from before its coroutine
                // ran never reaches the cloud, so only the first and the last open are deterministic.
                val deadline = System.currentTimeMillis() + 5_000
                while (cloud.accounts
                        .single()
                        .opened
                        .lastOrNull()
                        ?.first
                        ?.channelNo != 2 &&
                    System.currentTimeMillis() < deadline
                ) {
                    Thread.sleep(20)
                    ShadowLooper.idleMainLooper()
                }
                val opened =
                    cloud.accounts
                        .single()
                        .opened
                        .map { it.first.channelNo }
                assertThat(opened.first()).isEqualTo(1)
                assertThat(opened.last()).isEqualTo(2)
            }
        }
    }

    @Test
    fun `opens the camera at the grid position, in the moved arrangement, not the settings order`() {
        val serial = FakeCloudServices.SERIAL
        // The user moved channel 3 to the first tile (or made it the hero): the grid shows 3, 1, 2.
        app.graph.arrangementStore.lastLayout = GridLayoutMode.HERO_1_5
        app.graph.arrangementStore.setOrder(GridLayoutMode.HERO_1_5, listOf("$serial/3", "$serial/1", "$serial/2"))
        ActivityScenario.launch<FullscreenActivity>(FullscreenActivity.intent(app, 0)).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a ->
                assertThat(a.findViewById<TextView>(R.id.name).text.toString()).isEqualTo("Camera 3")
                a.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                ShadowLooper.idleMainLooper()
                assertThat(a.findViewById<TextView>(R.id.name).text.toString()).isEqualTo("Camera 1")
                a.finish()
                // Index 1 is where the grid shows camera 1 in this arrangement, so that tile is refocused.
                assertThat(shadowOf(a).resultIntent.getIntExtra(FullscreenActivity.EXTRA_INDEX, -1)).isEqualTo(1)
            }
        }
    }

    /** The dot in the tile's corner is the only liveness indicator: no text badge anywhere on the picture. */
    @Test
    fun `shows the live dot and no LIVE text`() {
        ActivityScenario.launch<FullscreenActivity>(FullscreenActivity.intent(app, 0)).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a ->
                assertThat(a.findViewById<View>(R.id.live_dot).visibility).isEqualTo(View.VISIBLE)
                val texts = ArrayList<String>()

                fun walk(v: View) {
                    if (v is TextView) texts += v.text.toString()
                    if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
                }
                walk(a.findViewById(android.R.id.content))
                assertThat(texts.filter { it.contains("LIVE", ignoreCase = true) }).isEmpty()
            }
        }
    }

    @Test
    fun `the name label follows Show camera names and Camera name position`() {
        app.graph.settingsStore.showCameraNames = true
        app.graph.settingsStore.cameraNamePosition = NamePosition.TOP_LEFT
        ActivityScenario.launch<FullscreenActivity>(FullscreenActivity.intent(app, 0)).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a ->
                val tile = a.findViewById<CameraTileView>(R.id.tile)
                assertThat(tile.showName).isTrue()
                assertThat(tile.namePosition).isEqualTo(NamePosition.TOP_LEFT)
                val name = a.findViewById<TextView>(R.id.name)
                assertThat(name.visibility).isEqualTo(View.VISIBLE)
                val gravity = (name.layoutParams as FrameLayout.LayoutParams).gravity
                assertThat(gravity).isEqualTo(Gravity.START or Gravity.TOP)
            }
        }
    }

    @Test
    fun `fullscreen quality setting is honoured`() {
        app.graph.settingsStore.fullscreenQuality = StreamQuality.MAIN
        ActivityScenario.launch<FullscreenActivity>(FullscreenActivity.intent(app, 0)).use {
            ShadowLooper.idleMainLooper()
            assertThat(
                cloud.accounts
                    .single()
                    .opened
                    .single()
                    .second,
            ).isEqualTo(StreamQuality.MAIN)
        }
    }

    @Test
    fun `switch-camera hint shows on entry, hides after the idle time and returns on a key`() {
        ActivityScenario.launch<FullscreenActivity>(FullscreenActivity.intent(app, 0)).use { scenario ->
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a ->
                val hint = a.findViewById<android.view.View>(R.id.hint)
                assertThat(hint.visibility).isEqualTo(android.view.View.VISIBLE)
                ShadowLooper.idleMainLooper(
                    app.graph.topBarAutoHideMillis + 1,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
                assertThat(hint.visibility).isEqualTo(android.view.View.INVISIBLE)
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                assertThat(hint.visibility).isEqualTo(android.view.View.VISIBLE)
                ShadowLooper.idleMainLooper(
                    app.graph.topBarAutoHideMillis / 2,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                ShadowLooper.idleMainLooper(
                    app.graph.topBarAutoHideMillis / 2 + 1,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
                assertThat(hint.visibility).isEqualTo(android.view.View.VISIBLE) // the second key restarted the timer
                ShadowLooper.idleMainLooper(app.graph.topBarAutoHideMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(hint.visibility).isEqualTo(android.view.View.INVISIBLE)
            }
        }
    }
}
