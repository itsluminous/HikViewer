package com.itsluminous.hikviewer.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.itsluminous.hikviewer.EspressoApp
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.grid.CameraGridLayout
import com.itsluminous.hikviewer.ui.grid.GridActivity
import com.itsluminous.hikviewer.ui.phone.PhoneCameraActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.not
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Touch navigation on a phone emulator (skipped on a television). Six cameras with signal; the
 * fake cloud streams the synthetic H.264 fixture through the real decoders and answers recording
 * searches with two segments a day.
 */
@RunWith(AndroidJUnit4::class)
class PhoneNavigationTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private lateinit var scenario: ActivityScenario<GridActivity>

    @Before
    fun loggedInGraph() {
        assumeFalse("phone-only navigation", FormFactor.isTelevision(app))
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        app.graph =
            TestGraph.create(
                app,
                FakeCloudServices(cameraCount = 8, signalCount = 6).apply {
                    playbackPaced = false
                    playbackHold = true
                    playbackFramesPerWindow = 24
                },
                EspressoApp.TOP_BAR_HIDE_MILLIS,
                playbackFactory = PlaybackFactory(),
            )
        runBlocking { app.graph.account.login("911234567890", "secret", Region.INDIA) }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        scenario = ActivityScenario.launch(GridActivity::class.java)
        // The suite describes a phone held upright. An emulator left rotation-locked in landscape
        // (user_rotation=1) would open the landscape fullscreen screen instead; lock this task to
        // portrait so the device's rotation setting cannot decide the outcome.
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        finishAllActivities()
        if (rotated) rotate(ROTATION_PORTRAIT)
    }

    private var rotated = false

    /**
     * Turns the device the way a hand would, through the system's rotation setting (the suite
     * leaves the accelerometer off), so the resumed Activity goes through a real configuration
     * change rather than a `requestedOrientation` it chose for itself.
     */
    private fun rotate(userRotation: Int) {
        rotated = userRotation != ROTATION_PORTRAIT
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation $userRotation")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        waitFor(ROTATION_MILLIS)
    }

    private fun shell(command: String) {
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
            .use { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
    }

    @Test
    fun theBarStaysOnScreenWithoutAnyKeyPress() {
        onView(withId(R.id.top_bar)).check(matches(isDisplayed()))
        onView(withId(R.id.layout_button)).check(matches(isDisplayed()))
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS + 700)
        onView(withId(R.id.top_bar)).check(matches(isDisplayed()))
        onView(withId(R.id.settings_button)).check(matches(isDisplayed()))
    }

    @Test
    fun tappingATileOpensThePortraitScreenWhereSwipesSwitchCameraAndTheBarScrubs() {
        onView(tile(1)).perform(click())
        onView(withId(R.id.live_dot)).check(matches(isDisplayed()))
        onView(withText("LIVE")).check(doesNotExist()) // the dot alone says whether pictures arrive
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 2")))
        val phone = resumedActivity()
        assert(phone is PhoneCameraActivity) { "expected the phone camera screen, got $phone" }
        assert(phone.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) { "not portrait" }

        onView(withId(R.id.video_frame)).perform(swipeLeft())
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 3")))
        onView(withId(R.id.video_frame)).perform(swipeRight())
        onView(withId(R.id.video_frame)).perform(swipeRight())
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 1")))

        // The day's segments arrive from the fake; a tap in the middle of the bar is noon, inside one.
        waitFor(SEGMENTS_MILLIS)
        onView(withId(R.id.time_bar)).perform(click())
        onView(withId(R.id.live_button)).check(matches(isEnabled()))
        // Espresso clicks a hair left of the exact centre, so the playhead lands just before noon.
        onView(withId(R.id.time_label)).check(matches(withText(anyOf(startsWith("11:5"), startsWith("12:0")))))
        onView(withId(R.id.live_button)).perform(click())
        onView(withId(R.id.live_button)).check(matches(not(isEnabled())))

        pressBack()
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
        onView(withId(R.id.top_bar)).check(matches(isDisplayed()))
    }

    /** Footage opened upright keeps playing sideways on the picture alone, and the bar is back when upright again. */
    @Test
    fun turningThePhoneSwapsTheCameraScreenLayoutAndKeepsTheFootage() {
        onView(tile(1)).perform(click())
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 2")))
        waitFor(SEGMENTS_MILLIS)
        onView(withId(R.id.time_bar)).perform(click())
        onView(withId(R.id.live_button)).check(matches(isEnabled()))
        onView(withId(R.id.time_label)).check(matches(withText(anyOf(startsWith("11:5"), startsWith("12:0")))))

        rotate(ROTATION_LANDSCAPE)
        val sideways = resumedActivity()
        assert(sideways is PhoneCameraActivity) { "expected the phone camera screen sideways, got $sideways" }
        assert(sideways.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) { "not landscape" }
        assert(!(sideways as PhoneCameraActivity).hasTimeline) { "the landscape layout has no time bar" }
        onView(withId(R.id.time_bar)).check(doesNotExist())
        onView(withId(R.id.speed_button)).check(doesNotExist())
        onView(withId(R.id.video_frame)).check(matches(isDisplayed()))
        onView(withText("LIVE")).check(doesNotExist()) // still the footage, and no text badge either way
        onView(withId(R.id.time_label)).check(matches(withText(anyOf(startsWith("11:5"), startsWith("12:0")))))
        val frame = sideways.findViewById<View>(R.id.video_frame)
        assert(frame.width == frame.height * 16 / 9) { "sideways picture is ${frame.width}x${frame.height}, not 16:9" }

        rotate(ROTATION_PORTRAIT)
        val upright = resumedActivity()
        assert((upright as PhoneCameraActivity).hasTimeline) { "the portrait layout has the time bar" }
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 2")))
        onView(withId(R.id.time_bar)).check(matches(isDisplayed()))
        onView(withId(R.id.live_button)).check(matches(isEnabled()))
        onView(withId(R.id.time_label)).check(matches(withText(anyOf(startsWith("11:5"), startsWith("12:0")))))
        pressBack()
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
    }

    private fun resumedActivity(): android.app.Activity {
        lateinit var activity: android.app.Activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
        }
        return activity
    }

    private fun startsWith(prefix: String): Matcher<String> = object : TypeSafeMatcher<String>() {
        override fun describeTo(description: Description) {
            description.appendText("starts with $prefix")
        }

        override fun matchesSafely(item: String): Boolean = item.startsWith(prefix)
    }

    private fun tile(position: Int): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("tile at grid position $position")
        }

        override fun matchesSafely(item: View): Boolean {
            val grid = item.parent as? CameraGridLayout ?: return false
            return grid.id == R.id.grid && grid.positionOf(item) == position
        }
    }

    private companion object {
        /** Time for the fake's recording search and the first live frames. */
        const val SEGMENTS_MILLIS = 1_500L

        /** `user_rotation` values: 0 is natural (portrait on a phone), 1 is turned 90° anticlockwise. */
        const val ROTATION_PORTRAIT = 0
        const val ROTATION_LANDSCAPE = 1

        /** The system's rotation animation plus the recreated Activity's first frames. */
        const val ROTATION_MILLIS = 2_500L
    }
}
