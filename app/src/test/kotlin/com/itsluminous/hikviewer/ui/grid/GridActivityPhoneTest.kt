package com.itsluminous.hikviewer.ui.grid

import android.content.pm.PackageManager
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenActivity
import com.itsluminous.hikviewer.ui.phone.PhoneCameraActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** The grid on a phone (touch screen, no leanback): the bar never hides and a tap opens the portrait screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp-port-xhdpi")
class GridActivityPhoneTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud = FakeCloudServices(cameraCount = 8, signalCount = 6)

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        app.graph = TestGraph.create(app, cloud, topBarAutoHideMillis = 300)
        kotlinx.coroutines.runBlocking { app.graph.account.login("a", "secret", Region.INDIA) }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
    }

    private fun ActivityScenario<GridActivity>.grid(block: (GridActivity, CameraGridLayout) -> Unit) {
        ShadowLooper.idleMainLooper()
        onActivity { a -> block(a, a.findViewById(R.id.grid)) }
    }

    @Test
    fun `portrait keeps the bar along the top and a tap opens the portrait camera screen`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                val bar = a.findViewById<LinearLayout>(R.id.top_bar)
                assertThat(bar.visibility).isEqualTo(View.VISIBLE)
                assertThat(bar.orientation).isEqualTo(LinearLayout.HORIZONTAL)
                assertThat((grid.layoutParams as FrameLayout.LayoutParams).topMargin)
                    .isEqualTo(a.resources.getDimensionPixelSize(R.dimen.top_bar_height))
                assertThat(grid.shape.columns).isEqualTo(2)
                // Icons only, the label kept for accessibility: 360 dp has no room for the words.
                listOf(R.id.layout_button to "Layout", R.id.settings_button to "Settings").forEach { (id, label) ->
                    val button = a.findViewById<android.widget.Button>(id)
                    assertThat(button.text.toString()).isEmpty()
                    assertThat(button.contentDescription.toString()).isEqualTo(label)
                    assertThat(button.compoundDrawables.any { it != null } || (button as MaterialButton).icon != null)
                        .isTrue()
                }
                ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS) // well past the TV's auto-hide
                assertThat(bar.visibility).isEqualTo(View.VISIBLE)
                grid.getChildAt(2).performClick()
                val next = shadowOf(a).nextStartedActivity
                assertThat(next.component!!.className).isEqualTo(PhoneCameraActivity::class.java.name)
                assertThat(next.getIntExtra(FullscreenActivity.EXTRA_INDEX, -1)).isEqualTo(2)
            }
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun `landscape stands the bar down the left edge and a tap opens the phone camera screen`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                val bar = a.findViewById<LinearLayout>(R.id.top_bar)
                val width = a.resources.getDimensionPixelSize(R.dimen.side_bar_width)
                assertThat(bar.visibility).isEqualTo(View.VISIBLE)
                assertThat(bar.orientation).isEqualTo(LinearLayout.VERTICAL)
                assertThat(bar.layoutParams.width).isEqualTo(width)
                assertThat(bar.layoutParams.height).isEqualTo(FrameLayout.LayoutParams.MATCH_PARENT)
                assertThat(a.findViewById<View>(R.id.title).visibility).isEqualTo(View.GONE)
                assertThat(a.findViewById<android.widget.Button>(R.id.layout_button).text.toString()).isEmpty()
                assertThat(a.findViewById<View>(R.id.layout_button).contentDescription.toString()).isEqualTo("Layout")
                assertThat((grid.layoutParams as FrameLayout.LayoutParams).marginStart).isEqualTo(width)
                assertThat(grid.shape.columns).isEqualTo(3) // 6 cameras: AUTO → 3x3, landscape
                grid.getChildAt(1).performClick()
                // The same screen as upright: it follows the phone, so the TV's fullscreen is never used here.
                val next = shadowOf(a).nextStartedActivity
                assertThat(next.component!!.className).isEqualTo(PhoneCameraActivity::class.java.name)
                assertThat(next.getIntExtra(FullscreenActivity.EXTRA_INDEX, -1)).isEqualTo(1)
            }
        }
    }

    /**
     * Sideways, the status is stacked (a number, then its word) so the bar is only as wide as its
     * icons; upright it is the TV's one-line text. Robolectric's players never decode, so every
     * tile counts as not live and the stacked text reads "0" / "live".
     */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun `landscape stacks the status in short lines and the bar is no wider than its dimen`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, _ ->
                val bar = a.findViewById<LinearLayout>(R.id.top_bar)
                val status = a.findViewById<TextView>(R.id.status)
                val width = a.resources.getDimensionPixelSize(R.dimen.side_bar_width)
                ShadowLooper.idleMainLooper()
                assertThat(bar.width).isEqualTo(width)
                assertThat(status.width).isAtMost(width)
                val lines = status.text.toString().split("\n")
                assertThat(lines).containsExactly("0", "live").inOrder()
                lines.forEach { assertThat(it.length).isAtMost(6) }
                // Each line fits the bar: the TextView draws exactly the lines given, none wrapped.
                assertThat(status.layout.lineCount).isEqualTo(lines.size)
                listOf(R.id.layout_button, R.id.settings_button).forEach { id ->
                    assertThat(a.findViewById<View>(id).width).isAtMost(width)
                }
            }
        }
    }

    @Test
    fun `portrait keeps the one-line status`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, _ ->
                val status = a.findViewById<TextView>(R.id.status)
                assertThat(status.text.toString()).doesNotContain("\n")
                assertThat(status.text.toString()).isEqualTo("6 cameras · 0 live")
            }
        }
    }

    /** The tile menu's Playback never forces a phone sideways: it opens the camera screen in its recorded mode. */
    @Test
    fun `the menu's Playback entry opens the phone camera screen in playback mode`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, _ ->
                a.onTileAction(2, TileAction.PLAYBACK)
                ShadowLooper.idleMainLooper()
                val next = shadowOf(a).nextStartedActivity
                assertThat(next.component!!.className).isEqualTo(PhoneCameraActivity::class.java.name)
                assertThat(next.getIntExtra(FullscreenActivity.EXTRA_INDEX, -1)).isEqualTo(2)
                assertThat(next.getBooleanExtra(PhoneCameraActivity.EXTRA_PLAYBACK, false)).isTrue()
            }
        }
    }

    /**
     * A phone keeps a layout per orientation (round 17). Upright the grid follows the portrait key,
     * turned the landscape key, each defaulting to AUTO; a pick in one orientation never changes
     * the other, and each layout keeps its own arrangement.
     */
    @Test
    fun `turning the phone switches to the layout stored for that orientation and back`() {
        val serial = FakeCloudServices.SERIAL
        app.graph.settingsStore.visibleCameraKeys = (1..8).map { "$serial/$it" }
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            pickTwoByThreeUprightAndMoveATile(scenario)
            RuntimeEnvironment.setQualifiers("w640dp-h360dp-land-xhdpi")
            scenario.recreate()
            scenario.grid { a, grid ->
                // Turned: the landscape key is still AUTO, 3 x 3 for eight cameras, in Settings order.
                assertThat(grid.mode).isEqualTo(GridLayoutMode.AUTO)
                assertThat(grid.shape.columns).isEqualTo(3)
                assertThat(grid.shape.rows).isEqualTo(3)
                assertThat(shownChannels(grid)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8).inOrder()
                a.findViewById<View>(R.id.layout_button).performClick()
                ShadowLooper.idleMainLooper()
                val menu = layoutDialog()
                assertThat(menu.titles()).containsExactly("Auto", "3 × 3", "1 + 5", "1 + 7").inOrder()
                assertThat(menu.checkedTitle()).isEqualTo("Auto")
                menu.pick(menu.titles().indexOf("1 + 5"))
                assertThat(app.graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.HERO_1_5)
                assertThat(app.graph.arrangementStore.lastLayoutPortrait).isEqualTo(GridLayoutMode.GRID_2X3)
            }
            scenario.grid { a, grid ->
                assertThat(grid.mode).isEqualTo(GridLayoutMode.HERO_1_5)
                assertThat(grid.shape.capacity).isEqualTo(6)
                assertThat(shownChannels(grid)).containsExactly(1, 2, 3, 4, 5, 6).inOrder() // its own arrangement
                assertThat(
                    a
                        .viewModels<GridViewModel>()
                        .value.playback.players.value,
                ).hasSize(6)
            }
            RuntimeEnvironment.setQualifiers("w360dp-h640dp-port-xhdpi")
            scenario.recreate()
            scenario.grid { a, grid ->
                // Upright again: 2 x 3 with the move made in it; the sideways pick changed nothing here.
                assertThat(grid.mode).isEqualTo(GridLayoutMode.GRID_2X3)
                assertThat(grid.shape.columns).isEqualTo(2)
                assertThat(grid.shape.rows).isEqualTo(3)
                assertThat(shownChannels(grid)).containsExactly(6, 2, 3, 4, 5, 1).inOrder()
                a.findViewById<View>(R.id.layout_button).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(layoutDialog().checkedTitle()).isEqualTo("2 × 3")
            }
        }
    }

    /** Upright on AUTO (2 x 4 for eight cameras), picks 2 x 3 from the menu and swaps its first and last tiles. */
    private fun pickTwoByThreeUprightAndMoveATile(scenario: ActivityScenario<GridActivity>) {
        scenario.grid { a, grid ->
            assertThat(grid.shape.columns).isEqualTo(2)
            assertThat(grid.shape.rows).isEqualTo(4)
            a.findViewById<View>(R.id.layout_button).performClick()
            ShadowLooper.idleMainLooper()
            val menu = layoutDialog()
            assertThat(menu.checkedTitle()).isEqualTo("Auto")
            menu.pick(menu.titles().indexOf("2 × 3"))
            assertThat(app.graph.arrangementStore.lastLayoutPortrait).isEqualTo(GridLayoutMode.GRID_2X3)
            assertThat(app.graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.AUTO)
        }
        scenario.grid { a, grid ->
            assertThat(grid.mode).isEqualTo(GridLayoutMode.GRID_2X3)
            assertThat(grid.shape.columns).isEqualTo(2)
            assertThat(grid.shape.rows).isEqualTo(3)
            assertThat(shownChannels(grid)).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
            a.viewModels<GridViewModel>().value.moveTile(0, 5)
            assertThat(shownChannels(grid)).containsExactly(6, 2, 3, 4, 5, 1).inOrder()
        }
    }

    /**
     * The landscape key never reaches an upright phone: whatever 0.1 or a sideways session left
     * there (3 x 3, 4 x 3, 4 x 4, a 1 + 7), upright is the portrait key, AUTO by default, so the
     * grid is two columns and the row count follows the camera count. Turned, the stored shape is back.
     */
    @Test
    fun `upright, a fresh store and any stored landscape layout draw the portrait auto shape`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { _, grid ->
                assertThat(app.graph.arrangementStore.lastLayoutPortrait).isEqualTo(GridLayoutMode.AUTO)
                assertThat(grid.shape.columns).isEqualTo(2) // 6 cameras: AUTO upright is 2 x 3
                assertThat(grid.shape.rows).isEqualTo(3)
            }
        }
        app.graph.arrangementStore.lastLayout = GridLayoutMode.GRID_4X3
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                assertThat(grid.mode).isEqualTo(GridLayoutMode.AUTO)
                assertThat(grid.shape.columns).isEqualTo(2)
                assertThat(grid.shape.rows).isEqualTo(3)
                assertThat(shownChannels(grid)).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
                a.findViewById<View>(R.id.layout_button).performClick()
                ShadowLooper.idleMainLooper()
                val menu = layoutDialog()
                assertThat(menu.titles()).containsNoneOf("3 × 3", "4 × 3", "4 × 4")
                assertThat(menu.checkedTitle()).isEqualTo("Auto")
                menu.dismiss()
            }
            RuntimeEnvironment.setQualifiers("w640dp-h360dp-land-xhdpi")
            scenario.recreate()
            scenario.grid { a, grid ->
                assertThat(grid.mode).isEqualTo(GridLayoutMode.GRID_4X3)
                assertThat(grid.shape.columns).isEqualTo(4)
                assertThat(grid.shape.rows).isEqualTo(3)
                a.findViewById<View>(R.id.layout_button).performClick()
                ShadowLooper.idleMainLooper()
                // Six cameras need no more than 3 x 3, so the 4 x 3 in force is not offered and nothing is marked.
                assertThat(layoutDialog().titles()).containsExactly("Auto", "3 × 3", "1 + 5").inOrder()
                assertThat(layoutDialog().checkedTitle()).isNull()
            }
        }
    }

    /** The user's account: 14 cameras upright are 2 x 7 whatever the landscape key holds, 3 x 3 included. */
    @Test
    fun `fourteen cameras upright are two columns of seven whatever the landscape layout is`() {
        val many = FakeCloudServices(cameraCount = 14, signalCount = 14)
        app.graph = TestGraph.create(app, many, topBarAutoHideMillis = 300)
        kotlinx.coroutines.runBlocking { app.graph.account.login("a", "secret", Region.INDIA) }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        listOf(GridLayoutMode.AUTO, GridLayoutMode.GRID_3X3, GridLayoutMode.GRID_4X3, GridLayoutMode.HERO_1_7)
            .forEach { stored ->
                app.graph.arrangementStore.lastLayout = stored
                ActivityScenario.launch(GridActivity::class.java).use { scenario ->
                    scenario.grid { a, grid ->
                        assertThat(grid.shape.columns).isEqualTo(2)
                        assertThat(grid.shape.rows).isEqualTo(7)
                        assertThat(shownChannels(grid)).hasSize(14)
                        val vm = a.viewModels<GridViewModel>().value
                        assertThat(vm.playback.players.value).hasSize(14)
                    }
                }
            }
    }

    /** Upright, the layout menu lists the portrait shapes and the even heroes, never the TV's grids; turned, the reverse. */
    @Test
    fun `the layout menu offers the portrait shapes upright and the landscape shapes turned`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, _ ->
                a.findViewById<View>(R.id.layout_button).performClick()
                ShadowLooper.idleMainLooper()
                val menu = layoutDialog()
                val titles = menu.titles()
                // Six cameras: one column up to 1 x 4, two columns up to 2 x 3, and 1 + 6 has no seventh camera.
                assertThat(titles).containsExactly("Auto", "1 × 2", "1 × 3", "1 × 4", "2 × 2", "2 × 3").inOrder()
                assertThat(titles).containsNoneOf("3 × 3", "4 × 3", "4 × 4")
                assertThat(menu.checkedTitle()).isEqualTo("Auto") // nothing stored yet: AUTO is in force
                menu.pick(titles.indexOf("1 × 3"))
                assertThat(app.graph.arrangementStore.lastLayoutPortrait).isEqualTo(GridLayoutMode.GRID_1X3)
                assertThat(app.graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.AUTO)
            }
            scenario.grid { _, grid ->
                assertThat(grid.shape.columns).isEqualTo(1)
                assertThat(grid.shape.rows).isEqualTo(3)
                assertThat(shownChannels(grid)).containsExactly(1, 2, 3).inOrder()
            }
            RuntimeEnvironment.setQualifiers("w640dp-h360dp-land-xhdpi")
            scenario.recreate()
            scenario.grid { a, grid ->
                assertThat(grid.shape.columns).isEqualTo(3) // the landscape key: AUTO, 3 x 3 for six
                assertThat(grid.shape.rows).isEqualTo(3)
                assertThat(shownChannels(grid)).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
                a.findViewById<View>(R.id.layout_button).performClick()
                ShadowLooper.idleMainLooper()
                val menu = layoutDialog()
                assertThat(menu.titles()).containsExactly("Auto", "3 × 3", "1 + 5").inOrder()
                assertThat(menu.checkedTitle()).isEqualTo("Auto")
            }
        }
    }

    /** The phone's Layout list marks the layout in force for this orientation: the stored one, AUTO when AUTO is stored. */
    @Test
    fun `the layout list checks the stored layout and auto when auto is stored`() {
        app.graph.arrangementStore.lastLayoutPortrait = GridLayoutMode.GRID_2X2
        app.graph.arrangementStore.lastLayout = GridLayoutMode.GRID_3X3 // the other orientation's: not this list's
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, _ ->
                a.findViewById<View>(R.id.layout_button).performClick()
                ShadowLooper.idleMainLooper()
                val menu = layoutDialog()
                assertThat(menu.checkedTitle()).isEqualTo("2 × 2")
                menu.pick(0) // Auto
                assertThat(app.graph.arrangementStore.lastLayoutPortrait).isEqualTo(GridLayoutMode.AUTO)
                assertThat(app.graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.GRID_3X3)
                a.findViewById<View>(R.id.layout_button).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(layoutDialog().checkedTitle()).isEqualTo("Auto")
            }
        }
    }

    /** The camera screen is told which orientation's grid sent the index, so it counts in the same arrangement. */
    @Test
    fun `a tap tells the camera screen the grid was upright`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                grid.getChildAt(1).performClick()
                val next = shadowOf(a).nextStartedActivity
                assertThat(next.getBooleanExtra(PhoneCameraActivity.EXTRA_PORTRAIT_GRID, false)).isTrue()
                a.onTileAction(2, TileAction.PLAYBACK)
                ShadowLooper.idleMainLooper()
                val playback = shadowOf(a).nextStartedActivity
                assertThat(playback.getBooleanExtra(PhoneCameraActivity.EXTRA_PORTRAIT_GRID, false)).isTrue()
            }
            RuntimeEnvironment.setQualifiers("w640dp-h360dp-land-xhdpi")
            scenario.recreate()
            scenario.grid { a, grid ->
                grid.getChildAt(1).performClick()
                val next = shadowOf(a).nextStartedActivity
                assertThat(next.getBooleanExtra(PhoneCameraActivity.EXTRA_PORTRAIT_GRID, true)).isFalse()
            }
        }
    }

    /** The open Layout list (the last dialog shown). */
    private fun layoutDialog(): AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog

    private fun AlertDialog.titles(): List<String> =
        (0 until listView.count).map { listView.adapter.getItem(it).toString() }

    /** The checked entry's label, or null when no entry is checked. */
    private fun AlertDialog.checkedTitle(): String? =
        listView.checkedItemPosition.takeIf { it >= 0 }?.let { listView.adapter.getItem(it).toString() }

    private fun AlertDialog.pick(index: Int) {
        listView.performItemClick(listView.getChildAt(index) ?: listView, index, listView.getItemIdAtPosition(index))
        ShadowLooper.idleMainLooper()
        assertThat(isShowing).isFalse()
    }

    /** Channel numbers of the tiles that have a cell, in grid order (the camera name is "Camera N"). */
    private fun shownChannels(grid: CameraGridLayout): List<Int> = (0 until grid.shape.capacity)
        .mapNotNull { grid.childAtPosition(it) as? CameraTileView }
        .filter { it.visibility == View.VISIBLE }
        .map {
            it.cameraName
                .toString()
                .removePrefix("Camera ")
                .toInt()
        }
}
