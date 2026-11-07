package com.itsluminous.hikviewer.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
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
import com.itsluminous.hikviewer.ui.grid.CameraTileView
import com.itsluminous.hikviewer.ui.grid.GridActivity
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import com.itsluminous.hikviewer.ui.phone.PhoneCameraActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
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
        // No Activity is left to idle on, so the rotation back is a plain wait (a failed test left it turned).
        if (rotated) rotate(ROTATION_PORTRAIT, idleOnUi = false)
    }

    private var rotated = false

    /**
     * Turns the device the way a hand would, through the system's rotation setting (the suite
     * leaves the accelerometer off), so the resumed Activity goes through a real configuration
     * change rather than a `requestedOrientation` it chose for itself.
     */
    private fun rotate(
        userRotation: Int,
        idleOnUi: Boolean = true,
    ) {
        rotated = userRotation != ROTATION_PORTRAIT
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation $userRotation")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        if (idleOnUi) waitFor(ROTATION_MILLIS) else Thread.sleep(ROTATION_MILLIS)
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
        onView(liveTextOnThePicture()).check(doesNotExist()) // the dot alone says whether pictures arrive
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
        assert(
            !(sideways as PhoneCameraActivity).isUpright,
        ) { "the landscape layout lays the controls over the picture" }
        onView(withId(R.id.video_frame)).check(matches(isDisplayed()))
        // The same transport is in the tree (an overlay, hidden by now: the rotation wait outlasts the idle time), on the footage.
        onView(withId(R.id.live_button)).check(matches(isEnabled()))
        onView(liveTextOnThePicture()).check(doesNotExist()) // still the footage, and no text badge either way
        onView(withId(R.id.time_label)).check(matches(withText(anyOf(startsWith("11:5"), startsWith("12:0")))))
        val frame = sideways.findViewById<View>(R.id.video_frame)
        assert(frame.width == frame.height * 16 / 9) { "sideways picture is ${frame.width}x${frame.height}, not 16:9" }

        rotate(ROTATION_PORTRAIT)
        val upright = resumedActivity()
        assert((upright as PhoneCameraActivity).isUpright) { "the portrait layout has the picture on top" }
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 2")))
        onView(withId(R.id.time_bar)).check(matches(isDisplayed()))
        onView(withId(R.id.live_button)).check(matches(isEnabled()))
        onView(withId(R.id.time_label)).check(matches(withText(anyOf(startsWith("11:5"), startsWith("12:0")))))
        pressBack()
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
    }

    /**
     * Playback from the tile menu on a phone held upright stays upright: the camera screen in its
     * recorded mode, footage under the bar. Turned, it is the footage alone, at the same position.
     */
    @Test
    fun playbackFromTheTileMenuRespectsOrientation() {
        onView(tile(1)).perform(longClick())
        onView(withText(R.string.tile_action_playback)).perform(click())
        waitFor(SEGMENTS_MILLIS)
        val phone = resumedActivity()
        assert(phone is PhoneCameraActivity) { "expected the phone camera screen, got $phone" }
        assert(phone.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) { "not portrait" }
        assert((phone as PhoneCameraActivity).isUpright) { "the portrait layout has the picture on top" }
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 2")))
        onView(withId(R.id.time_bar)).check(matches(isDisplayed()))
        onView(withId(R.id.live_button)).check(matches(isEnabled())) // footage, so Live is available
        onView(withId(R.id.speed_button)).check(matches(isEnabled()))
        onView(withId(R.id.time_label)).check(matches(withText(startsWith("23:5")))) // near the day's end
        onView(liveTextOnThePicture()).check(doesNotExist())

        rotate(ROTATION_LANDSCAPE)
        val sideways = resumedActivity()
        assert(sideways is PhoneCameraActivity) { "expected the phone camera screen sideways, got $sideways" }
        assert(sideways.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) { "not landscape" }
        onView(withId(R.id.video_frame)).check(matches(isDisplayed()))
        onView(withId(R.id.time_label)).check(matches(withText(startsWith("23:5"))))

        rotate(ROTATION_PORTRAIT)
        onView(withId(R.id.time_bar)).check(matches(isDisplayed()))
        onView(withId(R.id.time_label)).check(matches(withText(startsWith("23:5"))))
        pressBack()
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
    }

    /**
     * Sideways, the transport is an overlay on the picture: up for the idle time when the screen
     * opens, gone after it, back on a tap, and a tap on the bar scrubs into the footage from there.
     */
    @Test
    fun sidewaysATapShowsTheControlsAndTheyHideWhenIdle() {
        // Turn the grid first, so the camera screen opens sideways and its first seconds can be seen.
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        rotate(ROTATION_LANDSCAPE)
        onView(tile(1)).perform(click())
        val sideways = resumedActivity()
        assert(sideways is PhoneCameraActivity && !sideways.isUpright) { "expected the landscape camera screen" }
        // Shown for the first seconds, then gone.
        onView(withId(R.id.controls)).check(matches(isDisplayed()))
        onView(withId(R.id.help)).check(matches(withText(R.string.phone_help_landscape)))
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS + 700)
        onView(withId(R.id.controls)).check(matches(not(isDisplayed())))
        onView(withId(R.id.video_frame)).check(matches(isDisplayed()))

        // A tap on the picture brings them back; a second tap puts them away; a third brings them back.
        tapThePicture()
        onView(withId(R.id.controls)).check(matches(isDisplayed()))
        tapThePicture()
        onView(withId(R.id.controls)).check(matches(not(isDisplayed())))
        tapThePicture()
        onView(withId(R.id.controls)).check(matches(isDisplayed()))

        // Scrubbing from sideways opens the footage, as upright does, and the bar stays up for it.
        waitFor(SEGMENTS_MILLIS) // the day's segments; as long as the test idle time, so the overlay has gone again
        onView(withId(R.id.controls)).check(matches(not(isDisplayed())))
        tapThePicture()
        onView(withId(R.id.time_bar)).perform(click())
        onView(withId(R.id.live_button)).check(matches(isEnabled()))
        onView(withId(R.id.time_label)).check(matches(withText(anyOf(startsWith("11:5"), startsWith("12:0")))))
        onView(withId(R.id.controls)).check(matches(isDisplayed()))
        // A swipe on the picture still switches camera (back to live) and is not a tap: the overlay hides on time.
        onView(withId(R.id.video_frame)).perform(swipeLeftNearTop())
        onView(withId(R.id.live_button)).check(matches(not(isEnabled())))
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS + 700)
        onView(withId(R.id.controls)).check(matches(not(isDisplayed())))

        rotate(ROTATION_PORTRAIT)
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 3")))
        onView(withId(R.id.time_bar)).check(matches(isDisplayed()))
        pressBack()
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
    }

    /**
     * A tap in the upper part of the picture, clear of the controls overlay along its bottom, and
     * the wait for `GestureDetector` to confirm it as a single tap (it holds the verdict for the
     * double-tap window, which Espresso does not wait out).
     */
    private fun tapThePicture() {
        onView(
            withId(R.id.video_frame),
        ).perform(GeneralClickAction(Tap.SINGLE, GeneralLocation.TOP_CENTER, Press.FINGER, 0, 0))
        waitFor(TAP_CONFIRM_MILLIS)
    }

    /** A leftward swipe through the upper part of the view, clear of the controls overlay. */
    private fun swipeLeftNearTop(): ViewAction =
        GeneralSwipeAction(Swipe.FAST, GeneralLocation.TOP_RIGHT, GeneralLocation.TOP_LEFT, Press.FINGER)

    /**
     * Upright, the Layout menu offers the portrait shapes; 2 x 3 picked there is drawn 3 x 2 when
     * the phone is turned and 2 x 3 again when it is upright, every tile keeping its camera.
     */
    @Test
    fun aTwoColumnLayoutTurnsIntoItsCounterpartAndTheTilesKeepTheirCameras() {
        onView(withId(R.id.layout_button)).perform(click())
        onView(withText(R.string.layout_3x3)).check(doesNotExist()) // the TV's grids are not for a phone upright
        onView(withText(R.string.layout_2x3)).perform(click())
        waitFor(POPUP_DISMISS_MILLIS)
        assertGrid(columns = 2, rows = 3)
        (1..6).forEach { onView(tile(it - 1)).check(matches(tileNamed("Camera $it"))) }

        // The suite locks the grid upright (see loggedInGraph); this test is about turning it.
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        rotate(ROTATION_LANDSCAPE)
        assertGrid(columns = 3, rows = 2)
        assert(app.graph.arrangementStore.lastLayout == GridLayoutMode.GRID_2X3) { "the layout is still 2 x 3" }
        (1..6).forEach { onView(tile(it - 1)).check(matches(tileNamed("Camera $it"))) }

        rotate(ROTATION_PORTRAIT)
        assertGrid(columns = 2, rows = 3)
        (1..6).forEach { onView(tile(it - 1)).check(matches(tileNamed("Camera $it"))) }
    }

    /** The resumed grid's shape (the Activity is recreated by a rotation, so the scenario's reference is stale). */
    private fun assertGrid(
        columns: Int,
        rows: Int,
    ) {
        val activity = resumedActivity()
        assert(activity is GridActivity) { "expected the grid, got $activity" }
        val grid = activity.findViewById<CameraGridLayout>(R.id.grid)
        assert(grid.shape.columns == columns && grid.shape.rows == rows) {
            "grid is ${grid.shape.columns} x ${grid.shape.rows}, expected $columns x $rows"
        }
    }

    private fun tileNamed(name: String): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("tile named $name")
        }

        override fun matchesSafely(item: View): Boolean = (item as? CameraTileView)?.cameraName?.toString() == name
    }

    /** A "LIVE" label over the picture (the Live button below it, all caps by theme, is not one). */
    private fun liveTextOnThePicture(): Matcher<View> =
        allOf(withText("LIVE"), isDescendantOfA(withId(R.id.video_frame)))

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

        /** A popup menu's dismiss animation. */
        const val POPUP_DISMISS_MILLIS = 500L

        /** `GestureDetector` confirms a single tap once the double-tap timeout (300 ms) has passed. */
        const val TAP_CONFIRM_MILLIS = 450L
    }
}
