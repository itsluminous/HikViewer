package com.itsluminous.hikviewer.ui.playback

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PlaybackActivityTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud =
        FakeCloudServices(cameraCount = 4, signalCount = 3).apply {
            playbackPaced = false
            playbackHold = true
            playbackFramesPerWindow = 24
        }
    private val cameraKey = "${FakeCloudServices.SERIAL}/2"

    @Before
    fun setUp() {
        app.graph = TestGraph.create(app, cloud, topBarAutoHideMillis = 300)
        kotlinx.coroutines.runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertThat(condition()).isTrue()
    }

    /** A press and release through the window, as the remote delivers them. */
    private fun PlaybackActivity.key(keyCode: Int) {
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        ShadowLooper.idleMainLooper()
    }

    private fun PlaybackActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    private val account get() = cloud.accounts.single()

    @Test
    fun `lists the day, shows the controls and routes the D-pad by the logical control`() {
        ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(app, cameraKey)).use { scenario ->
            awaitUi { account.playbackOpened.isNotEmpty() }
            scenario.onActivity { a ->
                val today = LocalDate.now()
                assertThat(account.searched.single().second).isEqualTo(today)
                assertThat(a.findViewById<View>(R.id.controls).visibility).isEqualTo(View.VISIBLE)
                assertThat(a.text(R.id.date_chip)).contains(today.toString())
                assertThat(a.text(R.id.speed_button)).isEqualTo("1x")
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).highlighted).isTrue()
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).segments).hasSize(2)
                assertThat(a.findViewById<View>(R.id.status).visibility).isEqualTo(View.GONE)
                assertThat(a.text(R.id.help)).contains("keyframes")

                // UP → date chip; LEFT → yesterday.
                a.key(KeyEvent.KEYCODE_DPAD_UP)
                assertThat(a.findViewById<View>(R.id.date_chip).isActivated).isTrue()
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).highlighted).isFalse()
                a.key(KeyEvent.KEYCODE_DPAD_LEFT)
                awaitUi { account.searched.size == 2 }
                assertThat(a.text(R.id.date_chip)).contains(today.minusDays(1).toString())
                assertThat(a.text(R.id.time_label)).startsWith("06:00:0") // the fake delivered two seconds already

                // UP → speed; OK cycles; LEFT cycles back.
                a.key(KeyEvent.KEYCODE_DPAD_UP)
                assertThat(a.findViewById<View>(R.id.speed_button).isActivated).isTrue()
                a.key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertThat(a.text(R.id.speed_button)).isEqualTo("+2x")
                a.key(KeyEvent.KEYCODE_DPAD_LEFT)
                assertThat(a.text(R.id.speed_button)).isEqualTo("1x")

                // DOWN DOWN → bar; RIGHT seeks 10 s and the key release reopens there.
                a.key(KeyEvent.KEYCODE_DPAD_DOWN)
                a.key(KeyEvent.KEYCODE_DPAD_DOWN)
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).highlighted).isTrue()
                val opens = account.playbackOpened.size
                a.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                awaitUi { account.playbackOpened.size == opens + 1 }
                val reopened =
                    account.playbackOpened
                        .last()
                        .second.begin
                assertThat(reopened).isAtLeast(today.minusDays(1).atTime(6, 0, 10))
                assertThat(reopened).isAtMost(today.minusDays(1).atTime(6, 0, 14)) // 10 s past the ~2 s shown

                // OK pauses and says so; the controls hide after the idle time while paused too.
                a.key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertThat(a.text(R.id.status)).isEqualTo(a.getString(R.string.playback_paused))
                assertThat(a.findViewById<View>(R.id.status).visibility).isEqualTo(View.VISIBLE)
                ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(a.findViewById<View>(R.id.controls).visibility).isEqualTo(View.INVISIBLE)
                a.key(KeyEvent.KEYCODE_DPAD_DOWN)
                assertThat(a.findViewById<View>(R.id.controls).visibility).isEqualTo(View.VISIBLE)
            }
        }
    }

    @Test
    fun `clicks work without the D-pad and the bar seeks by fraction`() {
        ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(app, cameraKey)).use { scenario ->
            awaitUi { account.playbackOpened.isNotEmpty() }
            scenario.onActivity { a ->
                a.findViewById<View>(R.id.speed_button).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(a.text(R.id.speed_button)).isEqualTo("+2x")
                a.findViewById<View>(R.id.speed_button).performClick()
                a.findViewById<View>(R.id.speed_button).performClick()
                a.findViewById<View>(R.id.speed_button).performClick() // +10x → -10x
                ShadowLooper.idleMainLooper()
                assertThat(a.text(R.id.speed_button)).isEqualTo("-10x")
                a.findViewById<View>(R.id.date_chip).performClick()
                awaitUi { account.searched.size == 2 }
                assertThat(a.text(R.id.date_chip)).contains(LocalDate.now().minusDays(1).toString())
                val opens = account.playbackOpened.size
                a.findViewById<TimeBarView>(R.id.time_bar).onSeek!!.invoke(0.75f)
                awaitUi { account.playbackOpened.size > opens }
                assertThat(
                    account.playbackOpened
                        .last()
                        .second.begin,
                ).isEqualTo(LocalDate.now().minusDays(1).atTime(18, 0))
                assertThat(a.text(R.id.time_label)).isEqualTo("18:00:00")
            }
        }
    }

    @Test
    fun `an empty day says so and stop releases the player`() {
        cloud.recordings = { emptyList() }
        ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(app, cameraKey)).use { scenario ->
            awaitUi { account.searched.isNotEmpty() }
            scenario.onActivity { a ->
                assertThat(a.text(R.id.status)).isEqualTo(a.getString(R.string.playback_no_recordings))
                assertThat(account.playbackOpened).isEmpty()
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            ShadowLooper.idleMainLooper()
            scenario.onActivity { a ->
                assertThat(a.findViewById<View>(R.id.controls).visibility).isEqualTo(View.INVISIBLE)
            }
        }
    }
}
