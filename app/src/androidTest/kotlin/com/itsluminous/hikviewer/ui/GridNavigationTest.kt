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
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withParentIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.hikviewer.EspressoApp
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.ui.grid.GridActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.After
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

    private fun tile(index: Int): Matcher<View> = allOf(withParent(withId(R.id.grid)), withParentIndex(index))

    /** Sends a D-pad key to whatever has focus, as a remote does. */
    private fun dpad(keyCode: Int) {
        onView(isRoot()).perform(pressKey(keyCode))
    }

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
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
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
}
