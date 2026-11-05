package com.itsluminous.hikviewer.ui

import android.view.KeyEvent
import android.view.View
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
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.grid.CameraGridLayout
import com.itsluminous.hikviewer.ui.grid.CameraTileView
import com.itsluminous.hikviewer.ui.grid.GridActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the tiles draw on a TV while real decoders stream into their surfaces: a live dot in every
 * tile's top-right corner and a focus border on exactly the focused tile after every D-pad move.
 * The round-4 build showed one dot and lost the border on UP/DOWN because each `SurfaceView`'s
 * hole punch wiped the overlays drawn before it (see [CameraTileView]).
 */
@RunWith(AndroidJUnit4::class)
class TileDrawingTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()

    private lateinit var scenario: ActivityScenario<GridActivity>

    @Before
    fun loggedInGraph() {
        assumeTrue("D-pad suite, television only", FormFactor.isTelevision(app))
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        app.graph =
            TestGraph.create(
                app,
                FakeCloudServices(cameraCount = 16, signalCount = 12),
                EspressoApp.TOP_BAR_HIDE_MILLIS,
                playbackFactory = PlaybackFactory(),
            )
        runBlocking { app.graph.account.login("911234567890", "secret", Region.INDIA) }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        scenario = ActivityScenario.launch(GridActivity::class.java)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        finishAllActivities()
    }

    private fun dpad(keyCode: Int) {
        onView(isRoot()).perform(pressKey(keyCode))
    }

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
            check(borders[i] == (i == position)) { "tile $i border=${borders[i]} with focus on $position" }
        }
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
