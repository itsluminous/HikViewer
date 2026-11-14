package com.itsluminous.hikviewer.ui.phone

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.playback.RecordSegment
import com.itsluminous.hikviewer.data.StoredRecorderClocks
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.FixedRecorderClocks
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.UI_DEADLINE_MILLIS
import com.itsluminous.hikviewer.ui.UI_POLL_MILLIS
import com.itsluminous.hikviewer.ui.playback.PlaybackTimeline
import com.itsluminous.hikviewer.ui.playback.RecorderTimeLabel
import com.itsluminous.hikviewer.ui.playback.SeekController
import com.itsluminous.hikviewer.ui.playback.TimeBarView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** A phone held upright, with a touch screen and no leanback. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-port-xhdpi")
class PhoneCameraActivityTest {
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
        app.graph = TestGraph.create(app, cloud, topBarAutoHideMillis = IDLE_MILLIS)
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
    fun `opens live in portrait with the day's bar, scrubs into footage and comes back live`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 1)).use { scenario ->
            awaitUi { account.searched.isNotEmpty() && account.opened.isNotEmpty() }
            scenario.onActivity { a ->
                assertThat(a.isUpright).isTrue()
                assertThat(a.text(R.id.camera_name)).isEqualTo("Camera 2")
                a.assertNoLiveText()
                assertThat(a.findViewById<View>(R.id.live_button).isEnabled).isFalse()
                assertThat(a.findViewById<View>(R.id.speed_button).isEnabled).isFalse()
                assertThat(
                    account.opened
                        .single()
                        .first.channelNo,
                ).isEqualTo(2)
                assertThat(account.playbackOpened).isEmpty()
                val frame = a.findViewById<View>(R.id.video_frame)
                assertThat(frame.height).isEqualTo(frame.width * 9 / 16)
            }
            awaitUi { scenario.bar().segments.size == 2 }
            scenario.onActivity { a ->
                val bar = a.findViewById<TimeBarView>(R.id.time_bar)
                assertThat(bar.width).isGreaterThan(0)
                // Tap at noon: inside the first recorded segment.
                val tap = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, bar.width / 2f, bar.height / 2f, 0)
                bar.dispatchTouchEvent(tap)
                tap.recycle()
            }
            // The footage opens once the seek debounce has run out.
            ShadowLooper.idleMainLooper(SeekController.DEBOUNCE_MILLIS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
            awaitUi { account.playbackOpened.isNotEmpty() }
            scenario.onActivity { a ->
                val vm = a.viewModels<PhoneCameraViewModel>().value
                assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
                a.assertNoLiveText()
                assertThat(a.findViewById<View>(R.id.live_button).isEnabled).isTrue()
                assertThat(
                    account.playbackOpened
                        .single()
                        .second.begin,
                ).isEqualTo(LocalDate.now().atTime(12, 0))
                assertThat(a.text(R.id.time_label)).startsWith("12:00")
                a.findViewById<View>(R.id.live_button).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.LIVE)
                assertThat(a.findViewById<View>(R.id.live_button).isEnabled).isFalse()
            }
            awaitUi { account.opened.size == 2 }
        }
    }

    @Test
    fun `swiping the picture switches camera and Back returns the index`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitUi { account.opened.isNotEmpty() }
            scenario.onActivity { a ->
                val frame = a.findViewById<View>(R.id.video_frame)
                a.swipe(fromX = frame.width * 0.9f, toX = frame.width * 0.1f, y = frame.height / 2f)
                ShadowLooper.idleMainLooper()
                assertThat(
                    a
                        .viewModels<PhoneCameraViewModel>()
                        .value.index.value,
                ).isEqualTo(1)
                assertThat(a.text(R.id.camera_name)).isEqualTo("Camera 2")
                a.swipe(fromX = frame.width * 0.1f, toX = frame.width * 0.9f, y = frame.height / 2f)
                a.swipe(fromX = frame.width * 0.1f, toX = frame.width * 0.9f, y = frame.height / 2f)
                ShadowLooper.idleMainLooper()
                assertThat(a.text(R.id.camera_name)).isEqualTo("Camera 3") // wrapped around
                // A swipe over the controls below the picture is not a camera switch.
                a.swipe(fromX = frame.width * 0.1f, toX = frame.width * 0.9f, y = frame.height + 200f)
                ShadowLooper.idleMainLooper()
                assertThat(a.text(R.id.camera_name)).isEqualTo("Camera 3")
                a.finish()
                assertThat(shadowOf(a).resultIntent.getIntExtra("index", -1)).isEqualTo(2)
            }
        }
    }

    @Test
    fun `day arrows move the bar without leaving live`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitUi { account.searched.size == 1 }
            scenario.onActivity { a -> a.findViewById<View>(R.id.day_back).performClick() }
            awaitUi { account.searched.size == 2 }
            scenario.onActivity { a ->
                assertThat(a.text(R.id.date_chip)).isEqualTo(LocalDate.now().minusDays(1).toString())
                assertThat(
                    a
                        .viewModels<PhoneCameraViewModel>()
                        .value.mode.value,
                ).isEqualTo(PhoneCameraMode.LIVE)
                assertThat(account.playbackOpened).isEmpty()
                a.findViewById<View>(R.id.day_forward).performClick()
            }
            awaitUi { account.searched.size == 3 }
            scenario.onActivity { a -> assertThat(a.text(R.id.date_chip)).isEqualTo(LocalDate.now().toString()) }
        }
    }

    /**
     * The picture carries no "LIVE" text in either mode: the tile's dot alone says whether pictures
     * arrive, and the Live button's state says which stream it is.
     */
    private fun PhoneCameraActivity.assertNoLiveText() {
        val frame = findViewById<ViewGroup>(R.id.video_frame)
        val texts = ArrayList<String>()

        fun walk(v: View) {
            if (v is TextView) texts += v.text.toString()
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(frame)
        assertThat(texts.filter { it.contains("LIVE", ignoreCase = true) }).isEmpty()
        assertThat(findViewById<View>(R.id.live_dot).visibility).isEqualTo(View.VISIBLE)
    }

    private fun ActivityScenario<PhoneCameraActivity>.bar(): TimeBarView {
        lateinit var bar: TimeBarView
        onActivity { bar = it.findViewById(R.id.time_bar) }
        return bar
    }

    /** A fast horizontal drag through the window at height [y], as a finger would make it. */
    private fun PhoneCameraActivity.swipe(
        fromX: Float,
        toX: Float,
        y: Float,
    ) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val steps = 5
        dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, fromX, y, 0))
        for (i in 1..steps) {
            val x = fromX + (toX - fromX) * i / steps
            dispatchTouchEvent(MotionEvent.obtain(t0, t0 + i * 10L, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        dispatchTouchEvent(MotionEvent.obtain(t0, t0 + steps * 10L + 5, MotionEvent.ACTION_UP, toX, y, 0))
    }

    /**
     * Turning the phone swaps the layout under the same ViewModel: sideways the picture alone fills
     * the height and the footage keeps playing from where it was; upright again the bar is back at
     * that position.
     */
    @Test
    fun `turning the phone shows the picture alone and keeps the camera and the playback position`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 1)).use { scenario ->
            awaitUi { account.opened.isNotEmpty() && scenario.bar().segments.size == 2 }
            scenario.onActivity { a ->
                val bar = a.findViewById<TimeBarView>(R.id.time_bar)
                val tap = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, bar.width / 2f, bar.height / 2f, 0)
                bar.dispatchTouchEvent(tap)
                tap.recycle()
            }
            ShadowLooper.idleMainLooper(SeekController.DEBOUNCE_MILLIS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
            awaitUi { account.playbackOpened.size == 1 }
            val noon = LocalDate.now().atTime(12, 0)
            assertThat(
                account.playbackOpened
                    .single()
                    .second.begin,
            ).isEqualTo(noon)

            RuntimeEnvironment.setQualifiers("w640dp-h360dp-land-xhdpi")
            scenario.recreate()
            awaitUi { account.playbackOpened.size >= 2 } // the same engine reopened at its position
            scenario.onActivity { a ->
                assertThat(a.isUpright).isFalse()
                // The same transport, now an overlay on the picture, at the same position.
                assertThat(a.controlsShown).isTrue()
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).highlighted).isTrue()
                assertThat(a.findViewById<View>(R.id.speed_button).isEnabled).isTrue()
                val vm = a.viewModels<PhoneCameraViewModel>().value
                assertThat(vm.index.value).isEqualTo(1)
                assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
                a.assertNoLiveText()
                // The picture fills the height, 16:9, centred.
                val frame = a.findViewById<View>(R.id.video_frame)
                val root = a.findViewById<View>(R.id.root)
                assertThat(frame.height).isEqualTo(root.height - root.paddingTop - root.paddingBottom)
                assertThat(frame.width).isEqualTo(frame.height * 16 / 9)
                assertThat(a.text(R.id.time_label)).startsWith("12:00")
            }
            val reopened =
                account.playbackOpened
                    .last()
                    .second.begin
            assertThat(reopened).isAtLeast(noon)
            assertThat(reopened).isLessThan(noon.plusMinutes(1))
            assertThat(account.opened).hasSize(1) // live was not reopened: the footage kept the surface

            val openedSideways = account.playbackOpened.size
            RuntimeEnvironment.setQualifiers("w360dp-h640dp-port-xhdpi")
            scenario.recreate()
            awaitUi { account.playbackOpened.size > openedSideways }
            scenario.onActivity { a ->
                assertThat(a.isUpright).isTrue()
                val vm = a.viewModels<PhoneCameraViewModel>().value
                assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
                assertThat(a.text(R.id.camera_name)).isEqualTo("Camera 2")
                assertThat(a.findViewById<View>(R.id.live_button).isEnabled).isTrue()
                assertThat(a.text(R.id.time_label)).startsWith("12:00")
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).highlighted).isTrue()
            }
        }
    }

    /**
     * The tile menu's Playback on a phone held upright: the portrait layout in its recorded state,
     * footage playing under the bar, no live stream, and the phone is not forced sideways. A turn
     * shows the picture alone at the same position and speed; upright again, the bar is back.
     */
    @Test
    fun `opened for playback upright it plays footage under the bar and a turn keeps position and speed`() {
        val intent = PhoneCameraActivity.intent(app, 1, playback = true)
        ActivityScenario.launch<PhoneCameraActivity>(intent).use { scenario ->
            awaitUi { account.playbackOpened.isNotEmpty() }
            val lateEvening = LocalDate.now().atTime(23, 55) // 5 min before the last segment's end
            scenario.onActivity { a ->
                assertThat(a.isUpright).isTrue()
                assertThat(a.requestedOrientation).isEqualTo(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                assertThat(a.resources.configuration.orientation).isEqualTo(Configuration.ORIENTATION_PORTRAIT)
                val vm = a.viewModels<PhoneCameraViewModel>().value
                assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
                assertThat(account.opened).isEmpty()
                assertThat(
                    account.playbackOpened
                        .single()
                        .second.begin,
                ).isEqualTo(lateEvening)
                assertThat(a.findViewById<View>(R.id.live_button).isEnabled).isTrue()
                assertThat(a.findViewById<View>(R.id.speed_button).isEnabled).isTrue()
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).highlighted).isTrue()
                assertThat(a.text(R.id.time_label)).startsWith("23:5")
                a.findViewById<View>(R.id.speed_button).performClick() // 1x -> +2x
                ShadowLooper.idleMainLooper()
                assertThat(a.text(R.id.speed_button)).isEqualTo("+2x")
            }

            RuntimeEnvironment.setQualifiers("w640dp-h360dp-land-xhdpi")
            scenario.recreate()
            awaitUi { account.playbackOpened.size >= 2 }
            scenario.onActivity { a ->
                assertThat(a.isUpright).isFalse()
                val vm = a.viewModels<PhoneCameraViewModel>().value
                assertThat(vm.index.value).isEqualTo(1)
                assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
                assertThat(
                    vm.recorded.value!!
                        .state.value.speed.label,
                ).isEqualTo("+2x")
                assertThat(a.text(R.id.time_label)).startsWith("23:5")
                assertThat(account.opened).isEmpty() // still no live stream
            }

            RuntimeEnvironment.setQualifiers("w360dp-h640dp-port-xhdpi")
            scenario.recreate()
            scenario.onActivity { a ->
                assertThat(a.isUpright).isTrue()
                assertThat(a.text(R.id.camera_name)).isEqualTo("Camera 2")
                assertThat(a.text(R.id.speed_button)).isEqualTo("+2x")
                assertThat(a.text(R.id.time_label)).startsWith("23:5")
                assertThat(a.findViewById<View>(R.id.live_button).isEnabled).isTrue()
            }
        }
    }

    /** Playback asked for with the phone already sideways: the picture alone, on footage, Back returns the index. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun `opened for playback sideways it is fullscreen footage`() {
        val intent = PhoneCameraActivity.intent(app, 2, playback = true)
        ActivityScenario.launch<PhoneCameraActivity>(intent).use { scenario ->
            awaitUi { account.playbackOpened.isNotEmpty() }
            scenario.onActivity { a ->
                assertThat(a.isUpright).isFalse()
                assertThat(a.resources.configuration.orientation).isEqualTo(Configuration.ORIENTATION_LANDSCAPE)
                val vm = a.viewModels<PhoneCameraViewModel>().value
                assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
                assertThat(vm.camera!!.channelNo).isEqualTo(3)
                assertThat(account.opened).isEmpty()
                val frame = a.findViewById<View>(R.id.video_frame)
                assertThat(frame.width).isEqualTo(frame.height * 16 / 9)
                assertThat(a.text(R.id.time_label)).startsWith("23:5")
                a.finish()
                assertThat(shadowOf(a).resultIntent.getIntExtra("index", -1)).isEqualTo(2)
            }
        }
    }

    /** Sideways with the recorder's clock ahead: both lines are in the overlay and go away with it. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun `sideways the local time line sits under the recorder time and hides with the controls`() {
        val ist = Duration.ofMinutes(13 * 60 + 30)
        val cloud =
            FakeCloudServices(cameraCount = 4, signalCount = 3).apply {
                playbackPaced = false
                playbackHold = true
                playbackFramesPerWindow = 24
            }
        app.graph =
            TestGraph.create(app, cloud, topBarAutoHideMillis = IDLE_MILLIS) {
                FixedRecorderClocks(ist) { LocalDate.now().atTime(6, 43) }
            }
        kotlinx.coroutines.runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        val account = cloud.accounts.single()
        val intent = PhoneCameraActivity.intent(app, 2, playback = true)
        ActivityScenario.launch<PhoneCameraActivity>(intent).use { scenario ->
            awaitUi { account.playbackOpened.isNotEmpty() }
            scenario.onActivity { a ->
                val time = a.findViewById<TextView>(R.id.time_label)
                val local = a.findViewById<TextView>(R.id.local_time_label)
                assertThat(time.text.toString()).matches("23:5\\d:\\d\\d")
                assertThat(local.visibility).isEqualTo(View.VISIBLE)
                assertThat(local.text.toString()).matches("Local time  10:2\\d \\S+ \\(\\+13:30\\)")
                assertThat(local.text.toString()).contains(" ${RecorderTimeLabel.zoneName()} ")
                // Directly below line 1, inside the controls overlay.
                assertThat(local.top).isAtLeast(time.bottom)
                assertThat(a.findViewById<View>(R.id.controls).visibility).isEqualTo(View.VISIBLE)
            }
            ShadowLooper.idleMainLooper(IDLE_MILLIS + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
            scenario.onActivity { a ->
                assertThat(a.findViewById<View>(R.id.controls).visibility).isNotEqualTo(View.VISIBLE)
            }
        }
    }

    /**
     * Sideways, the controls overlay is shown for the idle time when the screen opens and hides
     * after it, and swiping the picture still switches camera while it is up.
     */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun `opened sideways the controls show for the idle time, then hide, and swiping switches camera`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitUi { account.opened.isNotEmpty() }
            scenario.onActivity { a ->
                assertThat(a.isUpright).isFalse()
                a.assertNoLiveText()
                assertThat(a.controlsShown).isTrue()
                assertThat(a.findViewById<View>(R.id.controls).visibility).isEqualTo(View.VISIBLE)
                assertThat(a.findViewById<View>(R.id.help).visibility).isEqualTo(View.VISIBLE)
                assertThat(a.text(R.id.help)).contains("tap the picture")
                assertThat(a.findViewById<View>(R.id.live_button).isEnabled).isFalse()
                val frame = a.findViewById<View>(R.id.video_frame)
                a.swipe(fromX = frame.width * 0.9f, toX = frame.width * 0.1f, y = frame.height * 0.3f)
                ShadowLooper.idleMainLooper()
                assertThat(
                    a
                        .viewModels<PhoneCameraViewModel>()
                        .value.index.value,
                ).isEqualTo(1)
                // The overlay hides after the idle time (1 s in this graph), like the TV's fullscreen hint.
                ShadowLooper.idleMainLooper(IDLE_MILLIS + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(a.controlsShown).isFalse()
                assertThat(a.findViewById<View>(R.id.controls).visibility).isNotEqualTo(View.VISIBLE)
                // Swiping still works with the controls away.
                a.swipe(fromX = frame.width * 0.9f, toX = frame.width * 0.1f, y = frame.height * 0.9f)
                ShadowLooper.idleMainLooper()
                assertThat(
                    a
                        .viewModels<PhoneCameraViewModel>()
                        .value.index.value,
                ).isEqualTo(2)
                assertThat(a.controlsShown).isFalse() // a fling is not a tap
            }
        }
    }

    /** A tap on the picture brings the hidden controls back; a tap while they are up puts them away at once. */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun `sideways a tap on the picture toggles the controls`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitUi { account.opened.isNotEmpty() }
            scenario.onActivity { a ->
                ShadowLooper.idleMainLooper(IDLE_MILLIS + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(a.controlsShown).isFalse()
                val frame = a.findViewById<View>(R.id.video_frame)
                a.tap(frame.width / 2f, frame.height * 0.3f)
                assertThat(a.controlsShown).isTrue()
                assertThat(a.findViewById<View>(R.id.controls).visibility).isEqualTo(View.VISIBLE)
                a.tap(frame.width / 2f, frame.height * 0.3f)
                assertThat(a.controlsShown).isFalse()
                a.tap(frame.width / 2f, frame.height * 0.3f)
                assertThat(a.controlsShown).isTrue()
                // Left alone, they hide again after the idle time.
                ShadowLooper.idleMainLooper(IDLE_MILLIS + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(a.controlsShown).isFalse()
            }
        }
    }

    /**
     * Working the controls keeps them up: a touch on the overlay restarts the timer and is never a
     * swipe, and a tap on the bar scrubs into the footage from sideways just as upright does.
     */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun `sideways a touch on the controls restarts their timer and the bar scrubs into footage`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 1)).use { scenario ->
            awaitUi { account.opened.isNotEmpty() && scenario.bar().segments.size == 2 }
            scenario.onActivity { a ->
                assertThat(a.controlsShown).isTrue()
                ShadowLooper.idleMainLooper(IDLE_MILLIS * 6 / 10, java.util.concurrent.TimeUnit.MILLISECONDS)
                val controls = a.findViewById<View>(R.id.controls)
                val bar = a.findViewById<TimeBarView>(R.id.time_bar)
                // A drag across the overlay (its help line): on the controls, so no camera switch, and the timer restarts.
                val y = centreInWindow(a.findViewById(R.id.help)).second.toFloat()
                a.swipe(fromX = controls.width * 0.9f, toX = controls.width * 0.1f, y = y)
                ShadowLooper.idleMainLooper(IDLE_MILLIS * 6 / 10, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(
                    a
                        .viewModels<PhoneCameraViewModel>()
                        .value.index.value,
                ).isEqualTo(1)
                assertThat(a.controlsShown).isTrue() // 1.2 s since the open, 0.6 s since the touch: still up
                ShadowLooper.idleMainLooper(IDLE_MILLIS * 6 / 10, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(a.controlsShown).isFalse()
                // Tap the picture to bring them back, then tap the bar at noon.
                a.tap(controls.width / 2f, 10f)
                assertThat(a.controlsShown).isTrue()
                val (barX, barY) = centreInWindow(bar)
                a.tap(barX.toFloat(), barY.toFloat())
            }
            ShadowLooper.idleMainLooper(SeekController.DEBOUNCE_MILLIS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
            awaitUi { account.playbackOpened.isNotEmpty() }
            scenario.onActivity { a ->
                val vm = a.viewModels<PhoneCameraViewModel>().value
                assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
                assertThat(vm.index.value).isEqualTo(1) // the tap on the bar was not a camera switch either
                assertThat(
                    account.playbackOpened
                        .single()
                        .second.begin,
                ).isEqualTo(LocalDate.now().atTime(12, 0))
                assertThat(a.text(R.id.time_label)).startsWith("12:00")
                assertThat(a.findViewById<View>(R.id.live_button).isEnabled).isTrue()
            }
        }
    }

    /**
     * A slow scrub along the bar that lasts longer than the idle time: the overlay stays up under
     * the finger for the whole drag and hides the idle time after it lifts, not in the middle.
     */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun `sideways a drag along the bar longer than the idle time keeps the controls up until it ends`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 1)).use { scenario ->
            awaitUi { account.opened.isNotEmpty() && scenario.bar().segments.size == 2 }
            scenario.onActivity { a ->
                val bar = a.findViewById<TimeBarView>(R.id.time_bar)
                val (barX, barY) = centreInWindow(bar)
                val y = barY.toFloat()
                val t0 = android.os.SystemClock.uptimeMillis()
                a.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, barX.toFloat(), y, 0))
                // Four moves, each 0.6 of the idle time apart: 2.4 idle times in all, no two events that far apart.
                repeat(4) { i ->
                    ShadowLooper.idleMainLooper(IDLE_MILLIS * 6 / 10, java.util.concurrent.TimeUnit.MILLISECONDS)
                    val t = android.os.SystemClock.uptimeMillis()
                    val x = barX + (i + 1) * bar.width / 10f
                    a.dispatchTouchEvent(MotionEvent.obtain(t0, t, MotionEvent.ACTION_MOVE, x, y, 0))
                    assertThat(a.controlsShown).isTrue()
                }
                val t1 = android.os.SystemClock.uptimeMillis()
                val endX = barX + 4 * bar.width / 10f
                a.dispatchTouchEvent(MotionEvent.obtain(t0, t1, MotionEvent.ACTION_UP, endX, y, 0))
                // The finger has lifted: still up for most of the idle time, gone once it has passed.
                ShadowLooper.idleMainLooper(IDLE_MILLIS * 6 / 10, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(a.controlsShown).isTrue()
                ShadowLooper.idleMainLooper(IDLE_MILLIS * 5 / 10, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(a.controlsShown).isFalse()
                // The drag was a scrub, not a camera switch.
                assertThat(
                    a
                        .viewModels<PhoneCameraViewModel>()
                        .value.index.value,
                ).isEqualTo(1)
            }
            awaitUi { account.playbackOpened.isNotEmpty() }
            scenario.onActivity { a ->
                val vm = a.viewModels<PhoneCameraViewModel>().value
                assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
            }
        }
    }

    /** Upright, the controls have their own half of the screen: no timer, and a tap on the picture changes nothing. */
    @Test
    fun `upright the controls never hide and a tap on the picture is nothing`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitUi { account.opened.isNotEmpty() }
            scenario.onActivity { a ->
                assertThat(a.isUpright).isTrue()
                assertThat(a.controlsShown).isTrue()
                ShadowLooper.idleMainLooper(IDLE_MILLIS + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(a.findViewById<View>(R.id.controls).visibility).isEqualTo(View.VISIBLE)
                val frame = a.findViewById<View>(R.id.video_frame)
                a.tap(frame.width / 2f, frame.height / 2f)
                assertThat(a.controlsShown).isTrue()
                assertThat(a.findViewById<View>(R.id.controls).visibility).isEqualTo(View.VISIBLE)
            }
        }
    }

    /** A finger down and up in one place; the confirmed tap is reported once the double-tap window has passed. */
    private fun PhoneCameraActivity.tap(
        x: Float,
        y: Float,
    ) {
        val t0 = android.os.SystemClock.uptimeMillis()
        dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0))
        dispatchTouchEvent(MotionEvent.obtain(t0, t0 + 50, MotionEvent.ACTION_UP, x, y, 0))
        ShadowLooper.idleMainLooper(TAP_CONFIRM_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** The centre of [view] in window coordinates, where the Activity's `dispatchTouchEvent` reads touches. */
    private fun centreInWindow(view: View): Pair<Int, Int> {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return (location[0] + view.width / 2) to (location[1] + view.height / 2)
    }

    /**
     * The user's case: a phone in Pacific time, the recorder on Indian time, 13 h 30 ahead and
     * already on the next date. Live, the playhead and label are the recorder's now with the phone's
     * time on a second line, and the bar shows the recorder's today; the tile menu's Playback opens
     * the newest footage.
     */
    @Test
    fun `live playhead, label and day are the recorder's when its clock is ahead of the phone's`() {
        val deviceToday = LocalDate.now()
        val deviceNow = deviceToday.atTime(11, 0, 0)
        val ist = Duration.ofMinutes(13 * 60 + 30)
        val recorderToday = deviceToday.plusDays(1) // 00:30 there
        val newestEnd = deviceNow.plus(ist).minusMinutes(8)
        val account = recorderAheadOfPhone(deviceNow, recorderToday, newestEnd)

        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 1)).use { scenario ->
            awaitUi { account.searched.size == 2 }
            awaitUi { scenario.bar().segments.isNotEmpty() }
            scenario.onActivity { a ->
                assertThat(account.searched.map { it.second })
                    .containsExactly(deviceToday, recorderToday)
                    .inOrder()
                assertThat(a.text(R.id.date_chip)).isEqualTo(recorderToday.toString())
                assertThat(a.text(R.id.time_label)).isEqualTo("00:30:00")
                // Live: line 2 is the phone's now, 11:00, under the recorder's 00:30.
                assertThat(a.findViewById<View>(R.id.local_time_label).visibility).isEqualTo(View.VISIBLE)
                assertThat(a.text(R.id.local_time_label))
                    .isEqualTo("Local time  11:00 ${RecorderTimeLabel.zoneName()} (+13:30)")
                val bar = a.findViewById<TimeBarView>(R.id.time_bar)
                assertThat(bar.positionFraction)
                    .isWithin(0.0001f)
                    .of(PlaybackTimeline.fractionOfDay(recorderToday.atTime(0, 30)))
            }
        }
        // Playback from the tile menu: the newest footage, in recorder terms, not the device's date.
        val playback = PhoneCameraActivity.intent(app, 1, playback = true)
        ActivityScenario.launch<PhoneCameraActivity>(playback).use { scenario ->
            awaitUi { account.playbackOpened.isNotEmpty() }
            scenario.onActivity { a ->
                val window = account.playbackOpened.last().second
                assertThat(window.begin).isEqualTo(newestEnd.minusMinutes(5))
                assertThat(a.text(R.id.date_chip)).isEqualTo(recorderToday.toString())
                // Recorded: line 2 is the phone's time of the playhead (which has run on a little), not its now.
                val minute = PlaybackTimeline.label(window.begin).substring(0, 6)
                assertThat(a.text(R.id.time_label)).startsWith(minute)
                assertThat(a.text(R.id.time_label)).doesNotContain("(")
                val deviceMinute =
                    window.begin
                        .minus(ist)
                        .toLocalTime()
                        .withSecond(0)
                        .toString()
                assertThat(a.text(R.id.local_time_label))
                    .isEqualTo("Local time  $deviceMinute ${RecorderTimeLabel.zoneName()} (+13:30)")
            }
        }
    }

    /**
     * A graph whose recorder's clock is learnt from footage: the device's day runs to midnight, the
     * recorder's next day up to [newestEnd], while the phone reads [deviceNow]. Returns the fake account.
     */
    private fun recorderAheadOfPhone(
        deviceNow: LocalDateTime,
        recorderToday: LocalDate,
        newestEnd: LocalDateTime,
    ): FakeCloudServices.FakeAccount {
        val deviceToday = deviceNow.toLocalDate()
        val cloud =
            FakeCloudServices(cameraCount = 4, signalCount = 3).apply {
                playbackPaced = false
                playbackHold = true
                playbackFramesPerWindow = 24
            }
        cloud.recordings = { day ->
            when (day) {
                deviceToday ->
                    listOf(RecordSegment(day.atTime(6, 0), recorderToday.atStartOfDay(), RecordSegment.TYPE_TIMING))
                recorderToday -> listOf(RecordSegment(day.atStartOfDay(), newestEnd, RecordSegment.TYPE_TIMING))
                else -> emptyList()
            }
        }
        app.graph =
            TestGraph.create(
                app,
                cloud,
                topBarAutoHideMillis = IDLE_MILLIS,
                recorderClocks = { settings -> StoredRecorderClocks(settings) { deviceNow } },
            )
        kotlinx.coroutines.runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        return cloud.accounts.single()
    }

    /** With the clocks in step nothing about the screen mentions the recorder. */
    @Test
    fun `no offset means a plain time label`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 1)).use { scenario ->
            awaitUi { account.searched.isNotEmpty() }
            scenario.onActivity { a ->
                assertThat(a.text(R.id.time_label)).matches("\\d\\d:\\d\\d:\\d\\d")
                assertThat(a.findViewById<View>(R.id.local_time_label).visibility).isEqualTo(View.GONE)
                assertThat(a.text(R.id.date_chip)).isEqualTo(LocalDate.now().toString())
            }
        }
    }

    private companion object {
        /** The graph's idle time before the landscape controls hide (5 s in production). */
        const val IDLE_MILLIS = 1_000L

        /** `GestureDetector` confirms a single tap once the double-tap timeout (300 ms) has passed. */
        const val TAP_CONFIRM_MILLIS = 350L
    }
}
