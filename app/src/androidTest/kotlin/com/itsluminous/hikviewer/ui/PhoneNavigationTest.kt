package com.itsluminous.hikviewer.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.recyclerview.widget.RecyclerView
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
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
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
        // Leave the emulator as a phone ships: auto-rotate on (rotate() and the toggle tests turn it off).
        shell("settings put system accelerometer_rotation 1")
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
        // Shown for the first seconds, then gone. The test graph's idle time is one second, and on a
        // slow CI emulator the rotation plus the launch can eat it before this line runs, so the
        // opening state is checked by the Robolectric test with an injected clock; here only the
        // hide is asserted.
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
     * A phone whose auto-rotate is off: the fullscreen icon in the upright picture's corner turns
     * the screen sideways (a real configuration change, `SENSOR_LANDSCAPE`), where the exit icon
     * sits in the controls overlay and comes back upright. Back while forced sideways goes upright
     * first, as a video player does, and only the next Back returns to the grid.
     */
    @Test
    fun withAutoRotateOffTheFullscreenButtonsTurnTheScreenAndBackComesUprightFirst() {
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation $ROTATION_PORTRAIT")
        onView(tile(1)).perform(click())
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 2")))
        onView(withId(R.id.fullscreen_button)).check(matches(isDisplayed()))
        onView(withId(R.id.fullscreen_button)).perform(click())
        waitFor(ROTATION_MILLIS)
        val sideways = resumedActivity()
        assert(sideways is PhoneCameraActivity && !sideways.isUpright) { "expected the sideways screen, got $sideways" }
        assert(sideways.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) { "not landscape" }
        assert(sideways.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            "forced orientation is ${sideways.requestedOrientation}"
        }
        onView(withId(R.id.fullscreen_button)).check(doesNotExist()) // the enter icon belongs to the upright layout
        // The exit icon shows and hides with the overlay: the rotation wait outlasts the idle time, a tap brings both back.
        onView(withId(R.id.controls)).check(matches(not(isDisplayed())))
        tapThePicture()
        onView(withId(R.id.controls)).check(matches(isDisplayed()))
        onView(withId(R.id.fullscreen_exit_button)).check(matches(isDisplayed()))
        onView(withId(R.id.fullscreen_exit_button)).check(matches(isDescendantOfA(withId(R.id.controls))))

        pressBack()
        waitFor(ROTATION_MILLIS)
        val upright = resumedActivity()
        assert(upright is PhoneCameraActivity && upright.isUpright) { "Back should go upright first, got $upright" }
        assert(upright.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            "forced orientation is ${upright.requestedOrientation}"
        }
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 2"))) // the camera rode along
        onView(withId(R.id.fullscreen_button)).check(matches(isDisplayed()))

        // The exit icon does the same turn as Back did.
        onView(withId(R.id.fullscreen_button)).perform(click())
        waitFor(ROTATION_MILLIS)
        tapThePicture()
        onView(withId(R.id.fullscreen_exit_button)).perform(click())
        waitFor(ROTATION_MILLIS)
        assert((resumedActivity() as PhoneCameraActivity).isUpright) { "the exit icon should go upright" }
        onView(withId(R.id.time_bar)).check(matches(isDisplayed()))

        pressBack()
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
    }

    /**
     * With auto-rotate on the phone turns itself, so the icons are gone; flipping the switch while
     * the screen is up shows and hides them through the setting's observer.
     */
    @Test
    fun withAutoRotateOnTheFullscreenButtonsAreAbsentAndFollowTheSwitch() {
        shell("settings put system accelerometer_rotation 1")
        onView(tile(1)).perform(click())
        onView(withId(R.id.camera_name)).check(matches(withText("Camera 2")))
        onView(withId(R.id.fullscreen_button)).check(matches(not(isDisplayed())))
        val phone = resumedActivity() as PhoneCameraActivity
        assert(phone.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) { "nothing may be forced" }

        shell("settings put system accelerometer_rotation 0")
        waitFor(SETTING_MILLIS)
        onView(withId(R.id.fullscreen_button)).check(matches(isDisplayed()))
        shell("settings put system accelerometer_rotation 1")
        waitFor(SETTING_MILLIS)
        onView(withId(R.id.fullscreen_button)).check(matches(not(isDisplayed())))

        pressBack() // one Back leaves, as before the toggle existed
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
     * A phone keeps a layout per orientation. 2 x 3 picked upright is the upright layout; turned,
     * the grid follows the sideways layout (AUTO until one is picked: 3 x 3 for six cameras), a
     * 1 + 5 picked there stays sideways, and upright again it is 2 x 3 as before.
     */
    @Test
    fun eachOrientationKeepsItsOwnLayoutAcrossRotations() {
        onView(withId(R.id.layout_button)).perform(click())
        onView(withText(R.string.layout_3x3)).check(doesNotExist()) // the TV's grids are not for a phone upright
        onView(withText(R.string.layout_2x3)).perform(click())
        waitFor(POPUP_DISMISS_MILLIS)
        assertGrid(GridLayoutMode.GRID_2X3, columns = 2, rows = 3)
        (1..6).forEach { onView(tile(it - 1)).check(matches(tileNamed("Camera $it"))) }
        assert(app.graph.arrangementStore.lastLayoutPortrait == GridLayoutMode.GRID_2X3) { "upright is 2 x 3" }
        assert(app.graph.arrangementStore.lastLayout == GridLayoutMode.AUTO) { "sideways is untouched" }

        // The suite locks the grid upright (see loggedInGraph); this test is about turning it.
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        rotate(ROTATION_LANDSCAPE)
        assertGrid(GridLayoutMode.AUTO, columns = 3, rows = 3)
        (1..6).forEach { onView(tile(it - 1)).check(matches(tileNamed("Camera $it"))) }
        onView(withId(R.id.layout_button)).perform(click())
        onView(withText(R.string.layout_2x3)).check(doesNotExist()) // the portrait shapes are not for a turned phone
        onView(withText(R.string.layout_1_5)).perform(click())
        waitFor(POPUP_DISMISS_MILLIS)
        assertGrid(GridLayoutMode.HERO_1_5, columns = 3, rows = 3)
        assert(app.graph.arrangementStore.lastLayout == GridLayoutMode.HERO_1_5) { "sideways is 1 + 5" }
        assert(app.graph.arrangementStore.lastLayoutPortrait == GridLayoutMode.GRID_2X3) { "upright is still 2 x 3" }

        rotate(ROTATION_PORTRAIT)
        assertGrid(GridLayoutMode.GRID_2X3, columns = 2, rows = 3)
        (1..6).forEach { onView(tile(it - 1)).check(matches(tileNamed("Camera $it"))) }
    }

    /**
     * Settings on a phone: each camera row starts with a drag handle. A finger that presses the
     * first row's handle and slides it down past the second row swaps the two, and letting go
     * persists the new order, which the grid then follows.
     */
    @Test
    fun draggingACameraRowByItsHandleReordersAndPersists() {
        onView(withId(R.id.settings_button)).perform(click())
        onView(withId(R.id.camera_list)).perform(scrollTo())
        onView(cameraRow(0)).check(matches(hasDescendant(withText("Camera 1"))))
        onView(cameraRow(1)).check(matches(hasDescendant(withText("Camera 2"))))
        onView(allOf(withId(R.id.drag_handle), isDescendantOfA(cameraRow(0)))).check(matches(isDisplayed()))
        onView(allOf(withId(R.id.handle), isDescendantOfA(cameraRow(0)))).check(matches(not(isDisplayed())))
        onView(allOf(withId(R.id.drag_handle), isDescendantOfA(cameraRow(0)))).perform(dragDownOneRow())
        waitFor(DROP_SETTLE_MILLIS)
        onView(cameraRow(0)).check(matches(hasDescendant(withText("Camera 2"))))
        onView(cameraRow(1)).check(matches(hasDescendant(withText("Camera 1"))))
        val serial = FakeCloudServices.SERIAL
        val keys = app.graph.settingsStore.visibleCameraKeys
        assert(keys?.take(3) == listOf("$serial/2", "$serial/1", "$serial/3")) { "stored order is $keys" }
        // Back to the grid: the first tile is the camera that was dragged to the top.
        pressBack()
        onView(tile(0)).check(matches(tileNamed("Camera 2")))
        onView(tile(1)).check(matches(tileNamed("Camera 1")))
    }

    /** A slow swipe from the handle's centre to a point one row and a half below it: past the next row's middle. */
    private fun dragDownOneRow(): ViewAction = GeneralSwipeAction(
        Swipe.SLOW,
        GeneralLocation.CENTER,
        { view -> GeneralLocation.CENTER.calculateCoordinates(view).also { it[1] += view.height * 1.5f } },
        Press.FINGER,
    )

    /** The camera row at [position] of Settings' list. */
    private fun cameraRow(position: Int): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("camera row $position")
        }

        override fun matchesSafely(item: View): Boolean {
            val list = item.parent as? RecyclerView ?: return false
            return list.id == R.id.camera_list && list.getChildAdapterPosition(item) == position
        }
    }

    /** The resumed grid's shape (the Activity is recreated by a rotation, so the scenario's reference is stale). */
    private fun assertGrid(
        mode: GridLayoutMode,
        columns: Int,
        rows: Int,
    ) {
        val activity = resumedActivity()
        assert(activity is GridActivity) { "expected the grid, got $activity" }
        val grid = activity.findViewById<CameraGridLayout>(R.id.grid)
        assert(grid.mode == mode) { "grid is ${grid.mode}, expected $mode" }
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

        /** The settings provider's change notification reaching the screen's observer. */
        const val SETTING_MILLIS = 1_000L

        /** A popup menu's dismiss animation. */
        const val POPUP_DISMISS_MILLIS = 500L

        /** `GestureDetector` confirms a single tap once the double-tap timeout (300 ms) has passed. */
        const val TAP_CONFIRM_MILLIS = 450L

        /** ItemTouchHelper's drop animation back into the row's cell. */
        const val DROP_SETTLE_MILLIS = 600L
    }
}
