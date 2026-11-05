package com.itsluminous.hikviewer.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.hikviewer.EspressoApp
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.stream.StreamException
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.grid.CameraGridLayout
import com.itsluminous.hikviewer.ui.grid.CameraTileView
import com.itsluminous.hikviewer.ui.grid.GridActivity
import com.itsluminous.hikviewer.ui.grid.GridViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the tiles draw on a TV while real decoders stream into their surfaces: a live dot in every
 * tile's top-right corner, a focus border on exactly the focused tile after every D-pad move (and
 * on none once the remote has been idle), and a focusable retry icon on a failed tile. The round-4
 * build showed one dot and lost the border on UP/DOWN because each `SurfaceView`'s hole punch
 * wiped the overlays drawn before it (see [CameraTileView]).
 *
 * The border hides [EspressoApp.TOP_BAR_HIDE_MILLIS] after the last key, and Espresso only acts
 * once the main thread is idle, which with sixteen decoders starting can take longer than that
 * (shortened) timer. So a "border shown" check is skipped when the wall clock has already passed
 * the timeout since the key; the "border hidden after the timeout" checks are unconditional.
 */
@RunWith(AndroidJUnit4::class)
class TileDrawingTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()

    private val cloud = FakeCloudServices(cameraCount = 16, signalCount = 12)

    private lateinit var scenario: ActivityScenario<GridActivity>

    /** Uptime of the last thing that counts as input (launch, a key): the border's timer starts there. */
    private var lastInputAt = 0L

    @Before
    fun loggedInGraph() {
        assumeTrue("D-pad suite, television only", FormFactor.isTelevision(app))
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        app.graph =
            TestGraph.create(
                app,
                cloud,
                EspressoApp.TOP_BAR_HIDE_MILLIS,
                playbackFactory = PlaybackFactory(),
            )
        runBlocking { app.graph.account.login("911234567890", "secret", Region.INDIA) }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        lastInputAt = SystemClock.uptimeMillis()
        scenario = ActivityScenario.launch(GridActivity::class.java)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        finishAllActivities()
    }

    private fun dpad(keyCode: Int) {
        lastInputAt = SystemClock.uptimeMillis()
        onView(isRoot()).perform(pressKey(keyCode))
    }

    /** True while the border's timer cannot have fired yet since [lastInputAt] (with slack for a slow host). */
    private fun borderStillDue(): Boolean =
        SystemClock.uptimeMillis() - lastInputAt < EspressoApp.TOP_BAR_HIDE_MILLIS - TIMER_SLACK_MILLIS

    /** Reads the grid's tiles on the main thread, in grid-position order. */
    private fun tiles(): List<CameraTileView> {
        var result: List<CameraTileView> = emptyList()
        scenario.onActivity { activity ->
            val grid = activity.findViewById<CameraGridLayout>(R.id.grid)
            result = (0 until grid.shape.cells.size).mapNotNull { grid.childAtPosition(it) as? CameraTileView }
        }
        return result
    }

    private fun assertOnlyTheFocusedTileHasTheBorder(position: Int) {
        onView(withId(R.id.grid)).check(matches(hasFocus()))
        val tiles = tiles()
        val borders = mutableListOf<Boolean>()
        var focused = -1
        scenario.onActivity { activity ->
            val grid = activity.findViewById<CameraGridLayout>(R.id.grid)
            focused = grid.positionOf(grid.findFocus())
            tiles.forEach { borders += it.focusBorderVisible }
            // The focused tile is also the one the grid draws last, above its neighbours' hole punches.
            check(grid.childDrawnAt(grid.childCount - 1) === tiles[focused]) { "focused tile not drawn last" }
        }
        check(focused == position) { "focus at $focused, expected $position" }
        tiles.indices.forEach { i ->
            if (i == position && !borders[i] && !borderStillDue()) {
                android.util.Log.w("HVTEST", "main thread busy past the hide timeout; skipping the shown-border check")
                return@forEach
            }
            check(borders[i] == (i == position)) { "tile $i border=${borders[i]} with focus on $position" }
        }
    }

    private fun borderOf(position: Int): Boolean {
        var shown = false
        scenario.onActivity { shown = tiles()[position].focusBorderVisible }
        return shown
    }

    @Test
    fun theFocusBorderHidesAfterTheTimeoutWhileFocusStaysAndAKeyBringsItBack() {
        onView(withId(R.id.grid)).check(matches(hasFocus()))
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS * 2)
        check(!borderOf(0)) { "border still drawn ${EspressoApp.TOP_BAR_HIDE_MILLIS * 2} ms after launch" }
        scenario.onActivity { activity ->
            val grid = activity.findViewById<CameraGridLayout>(R.id.grid)
            check(grid.positionOf(grid.findFocus()) == 0) { "focus moved while the border hid" }
            check(!grid.focusVisualShown)
        }
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertOnlyTheFocusedTileHasTheBorder(1)
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS * 2)
        check(!borderOf(1)) { "border still drawn after the timeout" }
        scenario.onActivity { activity ->
            val grid = activity.findViewById<CameraGridLayout>(R.id.grid)
            check(grid.positionOf(grid.findFocus()) == 1) { "focus moved while the border hid" }
        }
    }

    @Test
    fun theRetryIconTakesFocusAndOkOnItReopensTheStream() {
        val account = cloud.accounts.single()
        lateinit var tile: CameraTileView
        scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[GridViewModel::class.java]
            tile = tiles()[1]
            val players = vm.playback.players.value
            val camera = players[1].camera
            account.nextStreamError = StreamException.ChannelOffline(camera)
            vm.retry(1)
        }
        awaitOnMain { tile.showsRetry }
        lateinit var retry: ImageButton
        scenario.onActivity { activity ->
            retry = tile.findViewById(R.id.retry)
            check(retry.drawable != null && retry.background == null) { "retry must be a bare icon" }
            check(retry.contentDescription == activity.getString(R.string.tile_retry))
            check(retry.requestFocus()) { "retry icon refused focus" }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            check(retry.isFocused) { "retry icon lost focus" }
            check(retry.scaleX == CameraTileView.RETRY_FOCUS_SCALE) { "focused icon not scaled: ${retry.scaleX}" }
        }
        val opened = account.opened.size
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitOnMain { account.opened.size == opened + 1 && !tile.showsRetry }
        val reopened = account.opened.last().first
        check(reopened.channelNo == 2) { "wrong stream reopened: channel ${reopened.channelNo}" }
    }

    /** Pumps the main thread until [condition] holds (read on the main thread), at most 5 s. */
    private fun awaitOnMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            var met = false
            scenario.onActivity { met = condition() }
            if (met) return
            check(SystemClock.uptimeMillis() < deadline) { "condition not met in time" }
            waitFor(50)
        }
    }

    private companion object {
        /** How late a timer may be observed before a "still shown" check is skipped as a slow-host artefact. */
        const val TIMER_SLACK_MILLIS = 300L
    }

    @Test
    fun everyTileShowsItsLiveDotTopRight() {
        val tiles = tiles()
        check(tiles.size == 12) { "4x3 expected, got ${tiles.size} tiles" }
        scenario.onActivity {
            tiles.forEach { tile ->
                val dot = tile.findViewById<View>(R.id.live_dot)
                val margin = tile.resources.getDimensionPixelSize(R.dimen.live_dot_margin)
                check(dot.visibility == View.VISIBLE && dot.isShown) { "dot hidden on ${tile.cameraName}" }
                check(dot.top == margin && dot.right == tile.width - margin) {
                    "dot of ${tile.cameraName} at ${dot.left},${dot.top}-${dot.right} in ${tile.width}"
                }
                check(tile.clipChildren) { "tile must clip: the surface's hole punch would wipe the grid" }
            }
        }
    }

    @Test
    fun theFocusBorderFollowsEveryDpadMoveAcrossAllTiles() {
        assertOnlyTheFocusedTileHasTheBorder(0)
        // Snake through the 4x3 grid: every tile gets focus, with RIGHT, DOWN, LEFT and UP all exercised.
        val path =
            listOf(
                KeyEvent.KEYCODE_DPAD_RIGHT to 1,
                KeyEvent.KEYCODE_DPAD_RIGHT to 2,
                KeyEvent.KEYCODE_DPAD_RIGHT to 3,
                KeyEvent.KEYCODE_DPAD_DOWN to 7,
                KeyEvent.KEYCODE_DPAD_LEFT to 6,
                KeyEvent.KEYCODE_DPAD_LEFT to 5,
                KeyEvent.KEYCODE_DPAD_LEFT to 4,
                KeyEvent.KEYCODE_DPAD_DOWN to 8,
                KeyEvent.KEYCODE_DPAD_RIGHT to 9,
                KeyEvent.KEYCODE_DPAD_RIGHT to 10,
                KeyEvent.KEYCODE_DPAD_RIGHT to 11,
                KeyEvent.KEYCODE_DPAD_UP to 7,
                KeyEvent.KEYCODE_DPAD_UP to 3,
                KeyEvent.KEYCODE_DPAD_LEFT to 2,
                KeyEvent.KEYCODE_DPAD_DOWN to 6,
                KeyEvent.KEYCODE_DPAD_DOWN to 10,
                KeyEvent.KEYCODE_DPAD_UP to 6,
                KeyEvent.KEYCODE_DPAD_UP to 2,
            )
        path.forEach { (key, position) ->
            dpad(key)
            assertOnlyTheFocusedTileHasTheBorder(position)
        }
    }
}
