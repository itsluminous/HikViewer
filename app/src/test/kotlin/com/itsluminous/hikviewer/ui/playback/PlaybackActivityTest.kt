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
import com.itsluminous.hikviewer.fakes.FixedRecorderClocks
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.UI_DEADLINE_MILLIS
import com.itsluminous.hikviewer.ui.UI_POLL_MILLIS
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
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
        val deadline = System.currentTimeMillis() + UI_DEADLINE_MILLIS
        while (!condition() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(UI_POLL_MILLIS)
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
                assertThat(a.text(R.id.help)).contains("keyframe a second")

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

                // DOWN DOWN → bar; RIGHT seeks 10 s, the label moves at once and the stream reopens
                // there once the seek debounce has run out.
                a.key(KeyEvent.KEYCODE_DPAD_DOWN)
                a.key(KeyEvent.KEYCODE_DPAD_DOWN)
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).highlighted).isTrue()
                // Windows the seek opens begin at the sought time; a window the 1x transport opens
                // on its own when the fake stream runs out (which a loaded host lets happen in the
                // middle of this test) begins where the last one ended. Count the former only.
                val seekBegins = today.minusDays(1).atTime(6, 0, 10)..today.minusDays(1).atTime(6, 0, 20)

                fun seekOpens() = account.playbackOpened.count { it.second.begin in seekBegins }
                assertThat(seekOpens()).isEqualTo(0)
                val before = a.text(R.id.time_label)
                a.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                assertThat(a.text(R.id.time_label)).isNotEqualTo(before)
                // The label moved at once; the stream has not reopened before the debounce ran out.
                assertThat(seekOpens()).isEqualTo(0)
                ShadowLooper.idleMainLooper(
                    SeekController.DEBOUNCE_MILLIS + 1,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
                // 10 s past the ~2 s shown; the fake keeps delivering while a loaded host runs the test.
                awaitUi { seekOpens() == 1 }

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
    fun `a seek hold ends with the key even when the focus moved to another control meanwhile`() {
        ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(app, cameraKey)).use { scenario ->
            awaitUi { account.playbackOpened.isNotEmpty() }
            scenario.onActivity { a ->
                fun seconds(): Int {
                    val label = a.text(R.id.time_label)
                    return java.time.LocalTime
                        .parse(label)
                        .toSecondOfDay()
                }
                // Yesterday's footage starts at 06:00, so there is a day of room to seek forward into.
                a.key(KeyEvent.KEYCODE_DPAD_UP)
                a.key(KeyEvent.KEYCODE_DPAD_LEFT)
                awaitUi { account.searched.size == 2 }
                a.key(KeyEvent.KEYCODE_DPAD_DOWN)
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).highlighted).isTrue()
                // Hold RIGHT on the bar for four seconds (down, then auto-repeats every 100 ms as a
                // remote does, so the debounce never runs out mid-hold): the step grows to minutes.
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                repeat(40) {
                    ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, it + 1))
                }
                val before = seconds()
                a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 41))
                assertThat(seconds() - before).isAtLeast(60)
                // UP moves to the date chip while RIGHT is still held; then RIGHT goes up there.
                a.key(KeyEvent.KEYCODE_DPAD_UP)
                assertThat(a.findViewById<View>(R.id.date_chip).isActivated).isTrue()
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                ShadowLooper.idleMainLooper(
                    SeekController.DEBOUNCE_MILLIS + 1,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
                // Back on the bar, a fresh press is the smallest step again, not the minutes the hold reached.
                a.key(KeyEvent.KEYCODE_DPAD_DOWN)
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).highlighted).isTrue()
                val rested = seconds()
                a.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                val step = seconds() - rested
                assertThat(step).isAtLeast(10) // 10 s, plus whatever the reopened stream delivered meanwhile
                assertThat(step).isLessThan(30)
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
                val opensToday = account.playbackOpened.size
                a.findViewById<View>(R.id.date_chip).performClick()
                awaitUi { account.searched.size == 2 }
                assertThat(a.text(R.id.date_chip)).contains(LocalDate.now().minusDays(1).toString())
                // The search answers on an IO thread; the day's first hop opens once the main
                // thread has taken the segments. Only then is a seek acted on (LOADING ignores it).
                awaitUi { account.playbackOpened.size > opensToday }
                val opens = account.playbackOpened.size
                a.findViewById<TimeBarView>(R.id.time_bar).onSeek!!.invoke(0.75f)
                assertThat(a.text(R.id.time_label)).isEqualTo("18:00:00") // the label leads the stream
                ShadowLooper.idleMainLooper(
                    SeekController.DEBOUNCE_MILLIS + 1,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
                awaitUi { account.playbackOpened.size > opens }
                // At -10x the stepper opens the step's window (and the next one's ahead of it).
                assertThat(
                    account.playbackOpened[opens]
                        .second.begin,
                ).isEqualTo(LocalDate.now().minusDays(1).atTime(18, 0))

                // A drag along the bar: every sample moves the label, the stream reopens once where the finger rested.
                val bar = a.findViewById<TimeBarView>(R.id.time_bar)
                val samples = mutableListOf<Float>()
                val forward = bar.onSeek!!
                bar.onSeek = { f ->
                    samples += f
                    forward(f)
                }
                val dragged = account.playbackOpened.size
                bar.drag(0.76f, 0.78f, 0.80f)
                assertThat(samples).hasSize(3)
                assertThat(a.text(R.id.time_label)).isEqualTo("19:12:00")
                assertThat(account.playbackOpened).hasSize(dragged)
                ShadowLooper.idleMainLooper(
                    SeekController.DEBOUNCE_MILLIS + 1,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
                awaitUi { account.playbackOpened.size > dragged }
                // Still at -10x: the stepper opens where the finger rested and one step ahead of it.
                assertThat(account.playbackOpened).hasSize(dragged + PlaybackStepper.PREFETCH_STEPS)
                assertThat(
                    account.playbackOpened[dragged]
                        .second.begin,
                ).isEqualTo(LocalDate.now().minusDays(1).atTime(19, 12))
            }
        }
    }

    /** Press, move, release across the bar at the given fractions of its width. */
    private fun TimeBarView.drag(vararg fractions: Float) {
        val t0 = android.os.SystemClock.uptimeMillis()
        fractions.forEachIndexed { i, f ->
            val action =
                when (i) {
                    0 -> android.view.MotionEvent.ACTION_DOWN
                    fractions.lastIndex -> android.view.MotionEvent.ACTION_UP
                    else -> android.view.MotionEvent.ACTION_MOVE
                }
            val event = android.view.MotionEvent.obtain(t0, t0 + i * 20L, action, f * width, height / 2f, 0)
            dispatchTouchEvent(event)
            event.recycle()
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

    /** On a TV in another zone a second line gives the device's time; the bar's numbers stay the recorder's. */
    @Test
    fun `the time label names the recorder's offset when its clock differs`() {
        val cloud =
            FakeCloudServices(cameraCount = 4, signalCount = 3).apply {
                playbackPaced = false
                playbackHold = true
                playbackFramesPerWindow = 24
            }
        app.graph =
            TestGraph.create(app, cloud, topBarAutoHideMillis = 300) {
                FixedRecorderClocks(Duration.ofMinutes(13 * 60 + 30)) { LocalDate.now().atTime(6, 43) }
            }
        kotlinx.coroutines.runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        val account = cloud.accounts.single()
        ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(app, cameraKey)).use { scenario ->
            awaitUi { account.playbackOpened.isNotEmpty() }
            scenario.onActivity { a ->
                // The recorder's today, 20:13 there, is the day after the device's.
                assertThat(a.text(R.id.date_chip)).contains(LocalDate.now().toString())
                assertThat(a.text(R.id.time_label)).matches("23:55:0\\d")
                // Line 2: the device time of that playhead (13 h 30 earlier), the zone and the offset.
                val local = a.findViewById<TextView>(R.id.local_time_label)
                assertThat(local.visibility).isEqualTo(View.VISIBLE)
                assertThat(local.text.toString())
                    .isEqualTo("Local time  10:25 ${RecorderTimeLabel.zoneName()} (+13:30)")
            }
        }
    }
}
