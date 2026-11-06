package com.itsluminous.hikviewer.ui.phone

import android.content.pm.PackageManager
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
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.playback.SeekController
import com.itsluminous.hikviewer.ui.playback.TimeBarView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.LocalDate

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

    private fun PhoneCameraActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `opens live in portrait with the day's bar, scrubs into footage and comes back live`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 1)).use { scenario ->
            awaitUi { account.searched.isNotEmpty() && account.opened.isNotEmpty() }
            scenario.onActivity { a ->
                assertThat(a.hasTimeline).isTrue()
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
                assertThat(a.hasTimeline).isFalse()
                assertThat(a.findViewById<View?>(R.id.time_bar)).isNull()
                assertThat(a.findViewById<View?>(R.id.speed_button)).isNull()
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
                assertThat(a.hasTimeline).isTrue()
                val vm = a.viewModels<PhoneCameraViewModel>().value
                assertThat(vm.mode.value).isEqualTo(PhoneCameraMode.PLAYBACK)
                assertThat(a.text(R.id.camera_name)).isEqualTo("Camera 2")
                assertThat(a.findViewById<View>(R.id.live_button).isEnabled).isTrue()
                assertThat(a.text(R.id.time_label)).startsWith("12:00")
                assertThat(a.findViewById<TimeBarView>(R.id.time_bar).highlighted).isTrue()
            }
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun `opened sideways it is the picture alone, live, and swiping still switches camera`() {
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 0)).use { scenario ->
            awaitUi { account.opened.isNotEmpty() }
            scenario.onActivity { a ->
                assertThat(a.hasTimeline).isFalse()
                a.assertNoLiveText()
                assertThat(a.findViewById<View>(R.id.help).visibility).isEqualTo(View.VISIBLE)
                val frame = a.findViewById<View>(R.id.video_frame)
                a.swipe(fromX = frame.width * 0.9f, toX = frame.width * 0.1f, y = frame.height / 2f)
                ShadowLooper.idleMainLooper()
                assertThat(
                    a
                        .viewModels<PhoneCameraViewModel>()
                        .value.index.value,
                ).isEqualTo(1)
                // The help line hides after the idle time, like the TV's fullscreen hint.
                ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(a.findViewById<View>(R.id.help).visibility).isNotEqualTo(View.VISIBLE)
            }
        }
    }
}
