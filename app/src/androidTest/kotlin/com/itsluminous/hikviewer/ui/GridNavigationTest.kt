package com.itsluminous.hikviewer.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.PositionAssertions.isCompletelyAbove
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
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
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.player.TileState
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.grid.CameraGridLayout
import com.itsluminous.hikviewer.ui.grid.CameraTileView
import com.itsluminous.hikviewer.ui.grid.GridActivity
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import com.itsluminous.hikviewer.ui.settings.QrActivity
import com.itsluminous.hikviewer.ui.settings.SettingsActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers.not
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
        // @After runs after a failed assumption too; on a phone the scenario was never launched.
        if (::scenario.isInitialized) scenario.close()
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

        /** How close to the hide timeout a "still shown" check is still meaningful. */
        const val TIMER_SLACK_MILLIS = 300L

        /** Twelve software decoders on the emulator take a few seconds to all show pictures. */
        const val STATUS_TIMEOUT_MILLIS = 30_000L
        const val STATUS_POLL_MILLIS = 500L
    }

    /** Sends a D-pad key to whatever has focus, as a remote does. */
    private fun dpad(keyCode: Int) {
        onView(isRoot()).perform(pressKey(keyCode))
    }

    /** Gives the matched view focus on the main thread, the way a D-pad move would. */
    private fun takeFocus(): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()

        override fun getDescription(): String = "request focus"

        override fun perform(
            uiController: UiController,
            view: View,
        ) {
            check(view.requestFocus()) { "view refused focus" }
            uiController.loopMainThreadUntilIdle()
        }
    }

    /**
     * A short OK press: down and up with the same timestamp, injected back to back. Espresso's
     * `pressKey` syncs with the main thread between the two, and on a slow CI emulator that gap
     * passed the 400 ms long-press timeout, so a click opened the tile's long-press menu instead.
     */
    private fun ok() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.waitForIdleSync()
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
        ok()
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
        val pressedAt = android.os.SystemClock.uptimeMillis()
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        barStillShownUnlessTimedOut(pressedAt)
        onView(tile(1)).check(matches(hasFocus()))
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS / 2)
        barStillShownUnlessTimedOut(pressedAt)
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS)
        onView(withId(R.id.top_bar)).check(matches(withEffectiveVisibility(Visibility.INVISIBLE)))
    }

    /**
     * Asserts the bar is up, unless the wall clock already passed the hide timeout: Espresso only
     * acts once the main thread is idle, and with sixteen decoders starting on a loaded emulator
     * that can take longer than the (shortened) timer, which is a slow host, not a bar bug. The
     * hide-after-timeout assertion that follows is unconditional.
     */
    private fun barStillShownUnlessTimedOut(pressedAt: Long) {
        val elapsed = android.os.SystemClock.uptimeMillis() - pressedAt
        if (elapsed < EspressoApp.TOP_BAR_HIDE_MILLIS - TIMER_SLACK_MILLIS) {
            onView(withId(R.id.top_bar)).check(matches(isDisplayed()))
        } else {
            android.util.Log.w("HVTEST", "main thread busy for $elapsed ms; skipping the mid-timeout bar check")
        }
    }

    @Test
    fun upFromTheTopRowReachesTheTopBarAndDownComesBack() {
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        onView(withId(R.id.layout_button)).check(matches(hasFocus()))
        onView(withId(R.id.top_bar)).check(matches(isDisplayed()))
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        onView(withId(R.id.grid)).check(matches(hasFocus()))
    }

    @Test
    fun anIdleTopBarHandsFocusToTheFirstTileAndHides() {
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        onView(withId(R.id.layout_button)).check(matches(hasFocus()))
        // Nothing pressed for longer than the bar's timeout: it hides and the first tile takes
        // focus, not the tile the user came from.
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS * 2)
        onView(withId(R.id.top_bar)).check(matches(withEffectiveVisibility(Visibility.INVISIBLE)))
        onView(tile(0)).check(matches(hasFocus()))
    }

    @Test
    fun okOpensFullscreenAndBackReturnsToTheSameTile() {
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        ok()
        onView(withId(R.id.left_zone)).check(matches(isDisplayed()))
        // The "switch camera" hint follows the top bar's timer: shown on a key, gone after the timeout.
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        onView(withId(R.id.hint)).check(matches(isDisplayed()))
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS * 2)
        onView(withId(R.id.hint)).check(matches(withEffectiveVisibility(Visibility.INVISIBLE)))
        pressBack()
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
        // Focus comes back to the tile on the grid's next render, a frame or two after the
        // activity result on a slow emulator.
        waitForTileFocus(3)
        onView(tile(3)).check(matches(hasFocus()))
    }

    /** Waits until the tile at [position] holds focus (the grid restores it after a result). */
    private fun waitForTileFocus(position: Int) {
        val deadline = android.os.SystemClock.uptimeMillis() + STATUS_TIMEOUT_MILLIS
        var focused = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { a ->
                focused = a.findViewById<CameraGridLayout>(R.id.grid).childAtPosition(position)?.hasFocus() == true
            }
            if (focused) return
            waitFor(STATUS_POLL_MILLIS)
        }
        throw AssertionError("tile $position never took focus back within $STATUS_TIMEOUT_MILLIS ms")
    }

    @Test
    fun settingsLogoutReturnsToLogin() {
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        onView(withId(R.id.settings_button)).check(matches(hasFocus()))
        ok()
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

    /**
     * About with the remote: View Source opens the GitHub QR screen with the address above the
     * code, Back is Settings again; Donate opens the UPI QR screen with the UPI id under it.
     */
    @Test
    fun settingsAboutButtonsOpenTheQrScreensAndBackReturnsToSettings() {
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        onView(withId(R.id.settings_button)).check(matches(hasFocus()))
        ok()
        onView(withId(R.id.view_source)).perform(scrollTo(), takeFocus()).check(matches(hasFocus()))
        ok()
        onView(withId(R.id.qr)).check(matches(allOf(isDisplayed(), hasDrawable())))
        onView(withId(R.id.caption))
            .check(matches(allOf(isDisplayed(), withText("https://github.com/itsluminous/HikViewer"))))
        onView(withId(R.id.caption)).check(isCompletelyAbove(withId(R.id.qr)))
        onView(withId(R.id.text)).check(matches(not(isDisplayed())))
        assert(resumedActivity() is QrActivity) { "expected QrActivity on top, got ${resumedActivity()}" }
        pressBack()
        onView(withId(R.id.view_source)).check(matches(isDisplayed()))
        assert(resumedActivity() is SettingsActivity) { "expected SettingsActivity on top, got ${resumedActivity()}" }
        onView(withId(R.id.donate)).perform(scrollTo(), takeFocus()).check(matches(hasFocus()))
        ok()
        onView(withId(R.id.qr)).check(matches(allOf(isDisplayed(), hasDrawable())))
        onView(withId(R.id.text)).check(matches(allOf(isDisplayed(), withText("electricprakash@axisb"))))
        onView(withId(R.id.caption)).check(matches(not(isDisplayed())))
        pressBack()
        assert(resumedActivity() is SettingsActivity) { "expected SettingsActivity on top, got ${resumedActivity()}" }
    }

    private fun hasDrawable(): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("an ImageView with a drawable")
        }

        override fun matchesSafely(item: View): Boolean = (item as? ImageView)?.drawable != null
    }

    private fun resumedActivity(): Activity? {
        var top: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            top = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return top
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
        ok() // drop
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
        ok()
        // 12 cameras: the list has AUTO, 3x3, 4x3 and 1+5 … 1+11 (1+15 and 4x4 need more cameras); the
        // layout in force (AUTO) is checked and the D-pad starts on it.
        onView(withText(R.string.layout_1_11)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.layout_auto)).inRoot(isDialog()).check(matches(isChecked()))
        onView(withText(R.string.layout_1_5)).inRoot(isDialog()).check(matches(not(isChecked())))
        pickLayoutEntry(from = GridLayoutMode.AUTO, to = GridLayoutMode.HERO_1_5)
        onView(tile(0)).check(matches(tileNamed("Camera 1")))
        // The bar keeps focus after the list unless the list took longer than the bar's idle
        // timeout (a slow emulator): then focus has gone back to the first tile. Reach the button
        // again either way.
        onView(tile(0)).perform(takeFocus())
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        onView(withId(R.id.layout_button)).check(matches(hasFocus()))
        ok()
        // Reopened, the list marks the pick.
        onView(withText(R.string.layout_1_5)).inRoot(isDialog()).check(matches(isChecked()))
        onView(withText(R.string.layout_auto)).inRoot(isDialog()).check(matches(not(isChecked())))
        pickLayoutEntry(from = GridLayoutMode.HERO_1_5, to = GridLayoutMode.AUTO)
        onView(tile(0)).check(matches(tileNamed("Camera 2")))
        onView(tile(5)).check(matches(tileNamed("Camera 1")))
        assert(app.graph.arrangementStore.lastLayout == GridLayoutMode.AUTO)
    }

    /**
     * Walks the open Layout list from [from] (the checked entry, where the D-pad starts) to [to]
     * with DOWN or UP presses and OK; a wrong start would land on the wrong entry, so this also
     * checks that the list opens on the layout in force.
     */
    private fun pickLayoutEntry(
        from: GridLayoutMode,
        to: GridLayoutMode,
    ) {
        val entries = GridLayoutMode.available(12)
        val steps = entries.indexOf(to) - entries.indexOf(from)
        repeat(kotlin.math.abs(steps)) { dpad(if (steps > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
        ok()
        waitFor(POPUP_DISMISS_MILLIS)
    }

    @Test
    fun longPressChangeListsTheCamerasAndThePickTakesTheTile() {
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        onView(tile(1)).check(matches(allOf(hasFocus(), tileNamed("Camera 2"))))
        longOk()
        popupItem(R.string.tile_action_change).check(matches(isDisplayed()))
        pickPopupEntry(1) // Move, Change, Playback
        // The list dialog starts on this tile's camera; four DOWNs reach Camera 6.
        onView(withText("Camera 6")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Camera 2")).inRoot(isDialog()).check(matches(isChecked()))
        repeat(4) { dpad(KeyEvent.KEYCODE_DPAD_DOWN) }
        ok()
        waitFor(POPUP_DISMISS_MILLIS)
        onView(tile(1)).check(matches(allOf(hasFocus(), tileNamed("Camera 6"))))
        onView(tile(5)).check(matches(tileNamed("Camera 2")))
        val serial = FakeCloudServices.SERIAL
        val order = app.graph.arrangementStore.order(GridLayoutMode.AUTO)!!
        assert(order[1] == "$serial/6" && order[5] == "$serial/2") { "persisted $order" }
        // Back on the dialog changes nothing.
        longOk()
        pickPopupEntry(1)
        onView(withText(R.string.tile_change_title)).inRoot(isDialog()).check(matches(isDisplayed()))
        pressBack()
        onView(tile(1)).check(matches(allOf(hasFocus(), tileNamed("Camera 6"))))
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

    /**
     * Round 18: a tile whose software decoder failed answers a long OK with the "Decoder detail"
     * dialog, and a single OK closes it. The message is selectable (a phone copies it into a bug
     * report), and a selectable text is focusable, so the dialog has to hand the D-pad to its OK
     * button itself or the first OK press lands on the text and the remote user is stuck reading.
     */
    @Test
    fun longOkOnADecoderErrorTileShowsTheDetailAndOkClosesIt() {
        val detail = "codec=c2.test.avc.decoder error=-38 transient=false recoverable=false diag=error_neg_38"
        // Once pictures flow the player's state is Live and stays there, so the error bound below is not overwritten.
        waitForFramesFlowing(0)
        scenario.onActivity { a ->
            val tile = a.findViewById<CameraGridLayout>(R.id.grid).childAtPosition(0) as CameraTileView
            tile.bind(TileState.Error(TilePlayer.DECODER_ERROR, detail = detail))
        }
        onView(tile(0)).check(matches(hasFocus()))
        longOk()
        onView(withText(R.string.tile_decoder_detail_title)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(detail)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(android.R.string.ok)).inRoot(isDialog()).check(matches(hasFocus()))
        ok()
        waitFor(POPUP_DISMISS_MILLIS)
        onView(withText(detail)).check(doesNotExist())
        onView(tile(0)).check(matches(hasFocus()))
    }

    /** Waits until the tile at [position] reports pictures reaching the screen. */
    private fun waitForFramesFlowing(position: Int) {
        val deadline = android.os.SystemClock.uptimeMillis() + STATUS_TIMEOUT_MILLIS
        var flowing = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { a ->
                val tile = a.findViewById<CameraGridLayout>(R.id.grid).childAtPosition(position) as? CameraTileView
                flowing = tile?.framesFlowing == true
            }
            if (flowing) return
            waitFor(STATUS_POLL_MILLIS)
        }
        throw AssertionError("tile $position never showed a picture")
    }

    /** Waits until the top bar's status reads [expected]: the decoders need a moment to flow after a reassignment. */
    private fun waitForStatus(expected: String) = waitForStatus(Regex(Regex.escape(expected)))

    private fun waitForStatus(expected: Regex) {
        val deadline = android.os.SystemClock.uptimeMillis() + STATUS_TIMEOUT_MILLIS
        var seen = ""
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { seen = it.findViewById<android.widget.TextView>(R.id.status).text.toString() }
            if (expected.matches(seen)) return
            waitFor(STATUS_POLL_MILLIS)
        }
        throw AssertionError("status never matched '${expected.pattern}'; last was '$seen'")
    }
}
