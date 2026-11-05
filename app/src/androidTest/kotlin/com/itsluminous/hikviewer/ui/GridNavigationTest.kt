package com.itsluminous.hikviewer.ui

import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.hikviewer.EspressoApp
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.grid.CameraTileView
import com.itsluminous.hikviewer.ui.grid.GridActivity
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import kotlinx.coroutines.runBlocking
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-pad navigation on the TV emulator. The graph is logged in with the fake cloud (12 cameras with
 * signal, so AUTO resolves to 4x3) and the stored verification code, so streams run from the
 * synthetic H.264 fixture through the real decoders.
 */
@RunWith(AndroidJUnit4::class)
class GridNavigationTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()

    private lateinit var scenario: ActivityScenario<GridActivity>

    @Before
    fun loggedInGraph() {
        assumeTrue("D-pad suite, television only", FormFactor.isTelevision(app))
        // A TV has no touch screen, so it is never in touch mode; the instrumentation starts in it.
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        app.graph =
            TestGraph.create(
                app,
                FakeCloudServices(cameraCount = 16, signalCount = 12),
                EspressoApp.TOP_BAR_HIDE_MILLIS,
                // Real MediaCodec decoders: the fixture is clear H.264 the emulator can decode.
                playbackFactory = PlaybackFactory(),
            )
        runBlocking { app.graph.account.login("911234567890", "secret", Region.INDIA) }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        scenario = ActivityScenario.launch(GridActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        finishAllActivities()
    }

    /** The tile at grid [position] (not child index: a move changes positions, never the child list). */
    private fun tile(position: Int): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("tile at grid position $position")
        }

        override fun matchesSafely(item: View): Boolean {
            val grid = item.parent as? com.itsluminous.hikviewer.ui.grid.CameraGridLayout ?: return false
            return grid.id == R.id.grid && grid.positionOf(item) == position
        }
    }

    private companion object {
        /** A popup menu is its own window; its dismissal (and the return of window focus) is asynchronous. */
        const val POPUP_DISMISS_MILLIS = 500L
    }

    /** Sends a D-pad key to whatever has focus, as a remote does. */
    private fun dpad(keyCode: Int) {
        onView(isRoot()).perform(pressKey(keyCode))
    }

    /** Holds OK down past the long-press timeout, as a remote user does to open the tile menu. */
    private fun longOk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        waitFor(android.view.ViewConfiguration.getLongPressTimeout() + 300L)
        val up = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, up, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.waitForIdleSync()
    }

    /**
     * Long OK on the focused tile, then OK on the menu's first entry (Move), as a remote user does.
     * The entry is chosen with a key, not a tap: a tap would put the device in touch mode, where a
     * TV tile (not focusable in touch mode) could no longer take focus back.
     */
    private fun pickMove() {
        longOk()
        popupItem(R.string.tile_action_move).check(matches(isDisplayed()))
        pickPopupEntry(0)
    }

    /** Chooses entry [index] of the open popup menu with DOWN presses and OK (keys, never a tap: see [pickMove]). */
    private fun pickPopupEntry(index: Int) {
        repeat(index) { dpad(KeyEvent.KEYCODE_DPAD_DOWN) }
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(POPUP_DISMISS_MILLIS)
    }

    private fun tileNamed(name: String): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("tile named $name")
        }

        override fun matchesSafely(item: View): Boolean = (item as? CameraTileView)?.cameraName?.toString() == name
    }

    private fun popupItem(text: Int) = onView(
        androidx.test.espresso.matcher.ViewMatchers
            .withText(text),
    ).inRoot(
        androidx.test.espresso.matcher.RootMatchers
            .isPlatformPopup(),
    )

    @Test
    fun dpadMovesFocusAcrossTheGrid() {
        onView(isRoot()).check {
            v,
            _,
            ->
            android.util.Log.i("HVTEST", "focus before: ${v.findFocus()} touchMode=${v.isInTouchMode}")
        }
        onView(tile(0)).check(matches(hasFocus()))
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        onView(tile(1)).check(matches(hasFocus()))
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        onView(tile(5)).check(matches(hasFocus()))
        dpad(KeyEvent.KEYCODE_DPAD_LEFT)
        onView(tile(4)).check(matches(hasFocus()))
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        onView(tile(0)).check(matches(hasFocus()))
    }

    @Test
    fun topBarShowsOnAnyKeyWithoutStealingFocusAndHidesAfterTheTimeout() {
        onView(withId(R.id.top_bar)).check(matches(withEffectiveVisibility(Visibility.INVISIBLE)))
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        onView(withId(R.id.top_bar)).check(matches(isDisplayed()))
        onView(tile(1)).check(matches(hasFocus()))
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS / 2)
        onView(withId(R.id.top_bar)).check(matches(isDisplayed()))
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS)
        onView(withId(R.id.top_bar)).check(matches(withEffectiveVisibility(Visibility.INVISIBLE)))
    }

    @Test
    fun upFromTheTopRowReachesTheTopBarAndDownComesBack() {
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        onView(withId(R.id.layout_button)).check(matches(hasFocus()))
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS * 2)
        onView(withId(R.id.top_bar)).check(matches(isDisplayed())) // focused bar never auto-hides
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        onView(withId(R.id.grid)).check(matches(hasFocus()))
    }

    @Test
    fun okOpensFullscreenAndBackReturnsToTheSameTile() {
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        onView(withId(R.id.left_zone)).check(matches(isDisplayed()))
        // The "switch camera" hint follows the top bar's timer: shown on a key, gone after the timeout.
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        onView(withId(R.id.hint)).check(matches(isDisplayed()))
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS * 2)
        onView(withId(R.id.hint)).check(matches(withEffectiveVisibility(Visibility.INVISIBLE)))
        pressBack()
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
        onView(tile(3)).check(matches(hasFocus()))
    }

    @Test
    fun settingsLogoutReturnsToLogin() {
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        onView(withId(R.id.settings_button)).check(matches(hasFocus()))
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        onView(withId(R.id.logout)).perform(
            androidx.test.espresso.action.ViewActions
                .scrollTo(),
            click(),
        )
        onView(
            androidx.test.espresso.matcher.ViewMatchers
                .withText(R.string.settings_logout),
        ).inRoot(
            androidx.test.espresso.matcher.RootMatchers
                .isDialog(),
        ).perform(click())
        onView(withId(R.id.account)).check(matches(isDisplayed()))
        assert(!app.graph.account.isLoggedIn)
    }

    @Test
    fun longPressMoveDropsIntoThePersistedArrangementOfThatLayoutOnly() {
        onView(tile(0)).check(matches(allOf(hasFocus(), tileNamed("Camera 1"))))
        pickMove()
        onView(withId(R.id.move_hint)).check(matches(isDisplayed()))
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        onView(tile(1)).check(matches(allOf(hasFocus(), tileNamed("Camera 1"))))
        onView(tile(0)).check(matches(tileNamed("Camera 2")))
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        onView(tile(5)).check(matches(allOf(hasFocus(), tileNamed("Camera 1"))))
        dpad(KeyEvent.KEYCODE_DPAD_CENTER) // drop
        onView(withId(R.id.move_hint)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.grid)).check(matches(isDisplayed())) // OK dropped; it did not open fullscreen
        val serial = FakeCloudServices.SERIAL
        assert(
            app.graph.arrangementStore
                .order(GridLayoutMode.AUTO)!!
                .take(2) == listOf("$serial/2", "$serial/6"),
        )
        // The hero layout has its own (untouched) arrangement; coming back restores this one.
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        onView(withId(R.id.layout_button)).check(matches(hasFocus()))
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        // 12 cameras: the menu lists AUTO, 3x3, 4x3 and 1+5 … 1+11 (1+15 and 4x4 need more cameras).
        pickPopupEntry(GridLayoutMode.available(12).indexOf(GridLayoutMode.HERO_1_5))
        onView(tile(0)).check(matches(tileNamed("Camera 1")))
        onView(withId(R.id.layout_button)).check(matches(hasFocus())) // the bar keeps focus after the popup
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        pickPopupEntry(GridLayoutMode.available(12).indexOf(GridLayoutMode.AUTO))
        onView(tile(0)).check(matches(tileNamed("Camera 2")))
        onView(tile(5)).check(matches(tileNamed("Camera 1")))
        assert(app.graph.arrangementStore.lastLayout == GridLayoutMode.AUTO)
    }

    @Test
    fun backDuringAMoveRestoresTheArrangement() {
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        pickMove()
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        onView(tile(2)).check(matches(allOf(hasFocus(), tileNamed("Camera 2"))))
        pressBack()
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
        onView(tile(1)).check(matches(allOf(hasFocus(), tileNamed("Camera 2"))))
        onView(tile(2)).check(matches(tileNamed("Camera 3")))
        assert(
            app.graph.arrangementStore.order(GridLayoutMode.AUTO) ==
                app.graph.arrangementStore
                    .order(GridLayoutMode.AUTO)
                    ?.sortedBy { it.substringAfter('/').toInt() },
        )
    }
}
