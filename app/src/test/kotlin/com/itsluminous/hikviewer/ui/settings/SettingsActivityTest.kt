package com.itsluminous.hikviewer.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.data.NamePosition
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import com.itsluminous.hikviewer.ui.login.LoginActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud = FakeCloudServices(cameraCount = 5, signalCount = 3)
    private val serial = FakeCloudServices.SERIAL

    @Before
    fun setUp() {
        app.graph = TestGraph.create(app, cloud)
        kotlinx.coroutines.runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
    }

    private fun ActivityScenario<SettingsActivity>.rows(block: (SettingsActivity, RecyclerView) -> Unit) {
        ShadowLooper.idleMainLooper()
        onActivity { a -> block(a, a.findViewById(R.id.camera_list)) }
    }

    private fun RecyclerView.handle(i: Int): View = getChildAt(i).findViewById(R.id.handle)

    private fun RecyclerView.names(): List<String> =
        (0 until childCount).map { getChildAt(it).findViewById<TextView>(R.id.name).text.toString() }

    @Test
    fun `lists every camera, signal ones ticked first, and shows the version`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, list ->
                assertThat(list.childCount).isEqualTo(5)
                assertThat((0 until 5).map { list.getChildAt(it).findViewById<CheckBox>(R.id.check).isChecked })
                    .containsExactly(true, true, true, false, false)
                    .inOrder()
                assertThat(a.findViewById<TextView>(R.id.about).text.toString()).contains("HikViewer")
                assertThat(a.findViewById<View>(R.id.layout_spinner).isShown).isTrue()
            }
        }
    }

    /** Robolectric's default device is a phone held upright: the spinner lists the portrait shapes, like the grid menu. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `layout spinner, quality and overlay switches write the store`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                // Three visible cameras upright: AUTO, 1 x 2 and 1 x 3 (same filter as the grid menu).
                val layoutSpinner = a.findViewById<Spinner>(R.id.layout_spinner)
                assertThat(layoutSpinner.adapter.count).isEqualTo(3)
                assertThat((0 until 3).map { layoutSpinner.adapter.getItem(it).toString() })
                    .containsExactly("Auto", "1 × 2", "1 × 3")
                    .inOrder()
                layoutSpinner.setSelection(2)
                a.findViewById<SwitchCompat>(R.id.quality_main).isChecked = true
                assertThat(a.findViewById<SwitchCompat>(R.id.show_names).isChecked).isFalse()
                assertThat(a.findViewById<SwitchCompat>(R.id.show_fps).isChecked).isFalse()
                a.findViewById<SwitchCompat>(R.id.show_names).isChecked = true
                a.findViewById<SwitchCompat>(R.id.show_fps).isChecked = true
                ShadowLooper.idleMainLooper()
                // Upright, the spinner edits the portrait layout; the sideways one is untouched.
                assertThat(app.graph.arrangementStore.lastLayoutPortrait).isEqualTo(GridLayoutMode.GRID_1X3)
                assertThat(app.graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.AUTO)
                assertThat(app.graph.settingsStore.fullscreenQuality).isEqualTo(StreamQuality.MAIN)
                assertThat(app.graph.settingsStore.showCameraNames).isTrue()
                assertThat(app.graph.settingsStore.showFrameRate).isTrue()
                // No hero setting: the hero is position 0 of a 1 + N layout, chosen on the grid.
                assertThat(a.findViewById<View?>(R.id.layout_spinner)).isNotNull()
                GridLayoutMode.heroModes.forEach { mode -> assertThat(app.graph.arrangementStore.order(mode)).isNull() }
            }
        }
    }

    /**
     * The name-position row exists only while names are on, on a phone as on a TV: the switch
     * shows and hides it at once, and the chooser writes the store.
     */
    private fun namePositionRowFollowsTheSwitch() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                val row = a.findViewById<View>(R.id.name_position_row)
                val spinner = a.findViewById<Spinner>(R.id.name_position_spinner)
                val switch = a.findViewById<SwitchCompat>(R.id.show_names)
                assertThat(switch.isChecked).isFalse()
                assertThat(row.visibility).isEqualTo(View.GONE)
                // Directly below Show camera names, above Show frame rate.
                val column = row.parent as ViewGroup
                assertThat(column.indexOfChild(row)).isEqualTo(column.indexOfChild(switch) + 1)
                assertThat(column.indexOfChild(a.findViewById(R.id.show_fps))).isEqualTo(column.indexOfChild(row) + 1)
                switch.isChecked = true
                ShadowLooper.idleMainLooper()
                assertThat(row.visibility).isEqualTo(View.VISIBLE)
                assertThat(row.isShown).isTrue()
                assertThat(spinner.isEnabled).isTrue()
                assertThat((0 until spinner.adapter.count).map { spinner.adapter.getItem(it).toString() })
                    .containsExactly("Top left", "Bottom left", "Bottom right")
                    .inOrder()
                assertThat(spinner.selectedItemPosition).isEqualTo(1)
                val label = a.findViewById<TextView>(R.id.name_position_label)
                assertThat(label.text.toString()).isEqualTo("Camera name position")
                spinner.setSelection(0)
                ShadowLooper.idleMainLooper()
                assertThat(app.graph.settingsStore.cameraNamePosition).isEqualTo(NamePosition.TOP_LEFT)
                spinner.setSelection(2)
                ShadowLooper.idleMainLooper()
                assertThat(app.graph.settingsStore.cameraNamePosition).isEqualTo(NamePosition.BOTTOM_RIGHT)
                switch.isChecked = false
                ShadowLooper.idleMainLooper()
                assertThat(row.visibility).isEqualTo(View.GONE)
                // Hiding the row keeps the choice: it is back when names return.
                assertThat(app.graph.settingsStore.cameraNamePosition).isEqualTo(NamePosition.BOTTOM_RIGHT)
            }
        }
        // A fresh screen with names on and a stored corner shows the row with that corner selected.
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                assertThat(a.findViewById<View>(R.id.name_position_row).visibility).isEqualTo(View.GONE)
                app.graph.settingsStore.showCameraNames = true
            }
        }
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                assertThat(a.findViewById<View>(R.id.name_position_row).visibility).isEqualTo(View.VISIBLE)
                assertThat(a.findViewById<Spinner>(R.id.name_position_spinner).selectedItemPosition).isEqualTo(2)
            }
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun `on a television the name position row follows the names switch`() = namePositionRowFollowsTheSwitch()

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `on a phone the name position row follows the names switch`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        namePositionRowFollowsTheSwitch()
    }

    /** On a television the spinner has the TV's grids: AUTO and 3 x 3 for three cameras, never a portrait shape. */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun `on a television the layout spinner offers the landscape grids`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                val layoutSpinner = a.findViewById<Spinner>(R.id.layout_spinner)
                assertThat((0 until layoutSpinner.adapter.count).map { layoutSpinner.adapter.getItem(it).toString() })
                    .containsExactly("Auto", "3 × 3")
                    .inOrder()
                layoutSpinner.setSelection(1)
                ShadowLooper.idleMainLooper()
                assertThat(app.graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.GRID_3X3)
                assertThat(app.graph.arrangementStore.lastLayoutPortrait).isEqualTo(GridLayoutMode.AUTO)
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `ticking cameras updates the layout spinner on the same screen`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, list ->
                list.getChildAt(0).findViewById<CheckBox>(R.id.check).performClick()
                assertThat(
                    app.graph.settingsStore.visibleCameraKeys,
                ).containsExactly("$serial/2", "$serial/3").inOrder()
                // Three cameras offered AUTO, 1 x 2 and 1 x 3; with all five ticked 1 x 4, 2 x 2 and 2 x 3 fit too.
                list.getChildAt(0).findViewById<CheckBox>(R.id.check).performClick()
                list.getChildAt(3).findViewById<CheckBox>(R.id.check).performClick()
                list.getChildAt(4).findViewById<CheckBox>(R.id.check).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(a.findViewById<Spinner>(R.id.layout_spinner).adapter.count)
                    .isEqualTo(GridLayoutMode.available(5, portraitPhone = true).size)
                assertThat(GridLayoutMode.available(5, portraitPhone = true)).hasSize(6)
            }
        }
    }

    /** The TV flow, unchanged by the phone's drag handle: no drag handle on a TV, the text handle at the row's end. */
    @Test
    @Config(qualifiers = "television")
    fun `OK picks up a row, DOWN moves it, OK drops and saves the order`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, list ->
                assertThat(a.findViewById<TextView>(R.id.cameras_help).text.toString())
                    .isEqualTo("OK to pick up a camera, UP/DOWN to move it, OK to drop. Untick to hide.")
                (0 until list.childCount).forEach { i ->
                    assertThat(list.getChildAt(i).findViewById<View>(R.id.drag_handle).visibility).isEqualTo(View.GONE)
                    assertThat(list.handle(i).visibility).isEqualTo(View.VISIBLE)
                    assertThat(list.handle(i).isFocusable).isTrue()
                }
                list.handle(0).performClick()
                assertThat(list.getChildAt(0).findViewById<View>(R.id.moving).visibility).isEqualTo(View.VISIBLE)
                val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
                assertThat(list.handle(0).dispatchKeyEvent(down)).isTrue()
                ShadowLooper.idleMainLooper()
                assertThat(
                    list
                        .getChildAt(1)
                        .findViewById<TextView>(R.id.name)
                        .text
                        .toString(),
                ).isEqualTo("Camera 1")
                assertThat(list.getChildAt(1).findViewById<View>(R.id.moving).visibility).isEqualTo(View.VISIBLE)
                list.handle(1).dispatchKeyEvent(down)
                ShadowLooper.idleMainLooper()
                list.handle(2).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(
                    app.graph.settingsStore.visibleCameraKeys,
                ).containsExactly("$serial/2", "$serial/3", "$serial/1").inOrder()
                assertThat(list.getChildAt(2).findViewById<View>(R.id.moving).visibility).isEqualTo(View.GONE)
                assertThat(
                    list.names(),
                ).containsExactly("Camera 2", "Camera 3", "Camera 1", "Camera 4", "Camera 5").inOrder()
                // UP/DOWN on a row that is not picked up is ordinary navigation.
                assertThat(list.handle(0).dispatchKeyEvent(down)).isFalse()
            }
        }
    }

    /**
     * A phone has no D-pad: each row starts with a drag handle and the text handle is gone. A drag
     * (what ItemTouchHelper reports as the row passing its neighbours, then letting go) reorders
     * the rows and persists the order through the same store write as the TV's drop; the checkbox
     * still toggles visibility.
     */
    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `on a phone every row has a drag handle, no buttons, and a drag reorders and persists`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, list ->
                assertThat(a.findViewById<TextView>(R.id.cameras_help).text.toString())
                    .isEqualTo("Hold the handle to drag a camera into place. Untick to hide.")
                assertThat(list.childCount).isEqualTo(5)
                (0 until list.childCount).forEach { i ->
                    val row = list.getChildAt(i)
                    val handle = row.findViewById<ImageView>(R.id.drag_handle)
                    assertThat(handle.visibility).isEqualTo(View.VISIBLE)
                    assertThat(handle.drawable).isNotNull()
                    assertThat(handle.contentDescription.toString()).isEqualTo("Drag to reorder")
                    assertThat((row as ViewGroup).getChildAt(0)).isSameInstanceAs(handle) // on the left
                    assertThat(list.handle(i).visibility).isEqualTo(View.GONE)
                    assertThat(row.findViewById<View>(R.id.moving).visibility).isEqualTo(View.GONE)
                }
                val callback = dragCallback(list)
                assertThat(callback.isLongPressDragEnabled).isFalse()
                assertThat(callback.isItemViewSwipeEnabled).isFalse()
                val first = list.findViewHolderForAdapterPosition(0)!!
                val second = list.findViewHolderForAdapterPosition(1)!!
                assertThat(callback.getMovementFlags(list, first))
                    .isEqualTo(
                        ItemTouchHelper.Callback.makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0),
                    )
                // The finger carries row 0 past row 1: moved at once, persisted only on the drop.
                assertThat(callback.onMove(list, first, second)).isTrue()
                ShadowLooper.idleMainLooper()
                assertThat(
                    list.names(),
                ).containsExactly("Camera 2", "Camera 1", "Camera 3", "Camera 4", "Camera 5").inOrder()
                assertThat(app.graph.settingsStore.visibleCameraKeys).isNull()
                callback.clearView(list, first)
                assertThat(app.graph.settingsStore.visibleCameraKeys)
                    .containsExactly("$serial/2", "$serial/1", "$serial/3")
                    .inOrder()
                // The checkbox is untouched by the drag machinery.
                list.getChildAt(0).findViewById<CheckBox>(R.id.check).performClick()
                assertThat(
                    app.graph.settingsStore.visibleCameraKeys,
                ).containsExactly("$serial/1", "$serial/3").inOrder()
            }
        }
    }

    /** The drag rules the phone's ItemTouchHelper follows. */
    private fun dragCallback(list: RecyclerView): ItemTouchHelper.Callback =
        (list.adapter as CameraRowAdapter).dragCallback

    @Test
    fun `change encryption password verifies before storing`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                a.findViewById<View>(R.id.change_code).performClick()
                ShadowLooper.idleMainLooper()
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                dialog.findViewById<android.widget.EditText>(R.id.code_input)!!.setText("bad")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertThat(app.graph.settingsStore.verificationCode).isNull()
                assertThat(dialog.isShowing).isTrue()
                dialog.findViewById<android.widget.EditText>(R.id.code_input)!!.setText(FakeCloudServices.GOOD_CODE)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertThat(app.graph.settingsStore.verificationCode).isEqualTo(FakeCloudServices.GOOD_CODE)
            }
        }
    }

    @Test
    fun `logout confirms, clears everything and returns to login`() {
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                a.findViewById<View>(R.id.logout).performClick()
                ShadowLooper.idleMainLooper()
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(app.graph.account.isLoggedIn).isFalse()
                assertThat(app.graph.settingsStore.verificationCode).isNull()
                assertThat(cloud.logouts).isEqualTo(1)
                val next = shadowOf(a).nextStartedActivity
                assertThat(next.component?.className).isEqualTo(LoginActivity::class.java.name)
                assertThat(a.isFinishing).isTrue()
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `turning the phone keeps the camera selection`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { _, list ->
                list.getChildAt(0).findViewById<CheckBox>(R.id.check).performClick() // untick Camera 1
                list.getChildAt(3).findViewById<CheckBox>(R.id.check).performClick() // tick Camera 4
                assertThat(app.graph.settingsStore.visibleCameraKeys)
                    .containsExactly("$serial/2", "$serial/3", "$serial/4")
                    .inOrder()
            }
            RuntimeEnvironment.setQualifiers("w640dp-h360dp-land-xhdpi")
            scenario.recreate()
            scenario.rows { _, list ->
                // The list is as the user left it (rows keep their place until Settings is reopened).
                assertThat((0 until 5).map { list.getChildAt(it).findViewById<CheckBox>(R.id.check).isChecked })
                    .containsExactly(false, true, true, true, false)
                    .inOrder()
                assertThat(
                    (0 until 5).map {
                        list
                            .getChildAt(it)
                            .findViewById<TextView>(R.id.name)
                            .text
                            .toString()
                    },
                ).containsExactly("Camera 1", "Camera 2", "Camera 3", "Camera 4", "Camera 5").inOrder()
                assertThat(app.graph.settingsStore.visibleCameraKeys)
                    .containsExactly("$serial/2", "$serial/3", "$serial/4")
                    .inOrder()
            }
        }
    }

    /** On a phone the switch is offered, off by default, and flipping it changes this very window at once. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `keep screen on is offered on a phone and applies to the window as it is toggled`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                val section = a.findViewById<View>(R.id.screen_section)
                val switch = a.findViewById<SwitchCompat>(R.id.keep_screen_on)
                assertThat(section.visibility).isEqualTo(View.VISIBLE)
                assertThat(switch.isChecked).isFalse()
                assertThat(a.keepsScreenOn()).isFalse()
                switch.performClick()
                ShadowLooper.idleMainLooper()
                assertThat(app.graph.settingsStore.keepScreenOn).isTrue()
                assertThat(a.keepsScreenOn()).isTrue()
                switch.performClick()
                ShadowLooper.idleMainLooper()
                assertThat(app.graph.settingsStore.keepScreenOn).isFalse()
                assertThat(a.keepsScreenOn()).isFalse()
            }
        }
    }

    /** A TV never dims while video plays, so the section is not there. */
    @Test
    @Config(qualifiers = "television")
    fun `keep screen on is hidden on a television`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                assertThat(a.findViewById<View>(R.id.screen_section).visibility).isEqualTo(View.GONE)
                assertThat(a.findViewById<View>(R.id.keep_screen_on).isShown).isFalse()
                assertThat(a.keepsScreenOn()).isFalse()
            }
        }
    }

    private fun android.app.Activity.keepsScreenOn(): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    /** The text of every view tagged "section" under the column, in visual (depth-first) order. */
    private fun View.sectionTitles(): List<String> = when {
        tag == "section" && this is TextView -> listOf(text.toString())
        this is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).sectionTitles() }
        else -> emptyList()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `sections run in the required order on a phone, Screen included, Logout last`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                assertThat(a.findViewById<View>(R.id.column).sectionTitles())
                    .containsExactly(
                        "Grid layout",
                        "Tile overlays",
                        "Fullscreen quality",
                        "Screen",
                        "Visible cameras and order",
                        "Change encryption password",
                        "Backup",
                        "Restore",
                        "About",
                        "Logout",
                    ).inOrder()
            }
        }
    }

    /** Same order on a TV; the Screen section is gone, and no view steers the D-pad off the visual order. */
    @Test
    @Config(qualifiers = "television")
    fun `sections run in the required order on a television, without Screen`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                val column = a.findViewById<ViewGroup>(R.id.column)
                val shown = column.sectionTitles().filter { it != "Screen" }
                assertThat(a.findViewById<View>(R.id.section_screen).isShown).isFalse()
                assertThat(shown)
                    .containsExactly(
                        "Grid layout",
                        "Tile overlays",
                        "Fullscreen quality",
                        "Visible cameras and order",
                        "Change encryption password",
                        "Backup",
                        "Restore",
                        "About",
                        "Logout",
                    ).inOrder()
                val steered =
                    column.allViews().filter {
                        it.nextFocusDownId != View.NO_ID ||
                            it.nextFocusUpId != View.NO_ID
                    }
                assertThat(steered).isEmpty()
                // The last focusable thing on the screen is Logout.
                assertThat(column.allViews().last { it.isFocusable && it.isShown }.id).isEqualTo(R.id.logout)
            }
        }
    }

    private fun View.allViews(): List<View> {
        val children =
            if (this is ViewGroup) {
                (0 until childCount).flatMap {
                    getChildAt(
                        it,
                    ).allViews()
                }
            } else {
                emptyList()
            }
        return listOf(this) + children
    }

    /** A TV has neither a browser nor a UPI app: both About buttons open the QR screen, each with its own code and text. */
    @Test
    @Config(qualifiers = "television")
    fun `on a television View Source and Donate open the QR screens`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                // The plain-text address row is gone: the address is only ever the button's doing.
                assertThat(
                    a
                        .findViewById<ViewGroup>(R.id.column)
                        .allViews()
                        .filterIsInstance<TextView>()
                        .none { it !is Button && it.text.toString() == "https://github.com/itsluminous/HikViewer" },
                ).isTrue()
                val source = a.findViewById<Button>(R.id.view_source)
                assertThat(source.text.toString()).isEqualTo("View Source")
                assertThat(source.isFocusable).isTrue()
                source.performClick()
                val qr = shadowOf(a).nextStartedActivity
                assertThat(qr.component?.className).isEqualTo(QrActivity::class.java.name)
                assertThat(qr.getIntExtra(QrActivity.EXTRA_IMAGE, 0)).isEqualTo(R.drawable.github_qr)
                assertThat(qr.getIntExtra(QrActivity.EXTRA_CAPTION, 0)).isEqualTo(R.string.settings_source_url)
                assertThat(qr.getIntExtra(QrActivity.EXTRA_TEXT, 0)).isEqualTo(0)
                val donate = a.findViewById<Button>(R.id.donate)
                assertThat(donate.text.toString()).isEqualTo("Donate via UPI")
                assertThat(donate.isFocusable).isTrue()
                donate.performClick()
                val upi = shadowOf(a).nextStartedActivity
                assertThat(upi.component?.className).isEqualTo(QrActivity::class.java.name)
                assertThat(upi.getIntExtra(QrActivity.EXTRA_IMAGE, 0)).isEqualTo(R.drawable.upi_qr)
                assertThat(upi.getIntExtra(QrActivity.EXTRA_CAPTION, 0)).isEqualTo(0)
                assertThat(upi.getIntExtra(QrActivity.EXTRA_TEXT, 0)).isEqualTo(R.string.upi_id)
                assertThat(shadowOf(a).nextStartedActivity).isNull()
                assertThat(ShadowDialog.getLatestDialog()).isNull()
            }
        }
    }

    /** A phone opens the links themselves: the address in the browser, the payment URI in the UPI app. */
    @Test
    fun `on a phone View Source and Donate start ACTION_VIEW with the exact URIs`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                a.findViewById<Button>(R.id.view_source).performClick()
                val source = shadowOf(a).nextStartedActivity
                assertThat(source.action).isEqualTo(Intent.ACTION_VIEW)
                assertThat(source.dataString).isEqualTo("https://github.com/itsluminous/HikViewer")
                a.findViewById<Button>(R.id.donate).performClick()
                val upi = shadowOf(a).nextStartedActivity
                assertThat(upi.action).isEqualTo(Intent.ACTION_VIEW)
                assertThat(upi.dataString).isEqualTo("upi://pay?pa=electricprakash@axisb")
                assertThat(shadowOf(a).nextStartedActivity).isNull()
            }
        }
    }

    /** A phone without a UPI app: the ACTION_VIEW is refused, so the UPI QR screen opens instead of a crash. */
    @Test
    fun `on a phone without a UPI app Donate falls back to the QR screen`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                shadowOf(app).checkActivities(true) // Robolectric now refuses an intent no activity resolves
                a.findViewById<Button>(R.id.donate).performClick()
                val next = shadowOf(a).nextStartedActivity
                assertThat(next.component?.className).isEqualTo(QrActivity::class.java.name)
                assertThat(next.getIntExtra(QrActivity.EXTRA_IMAGE, 0)).isEqualTo(R.drawable.upi_qr)
                assertThat(a.isFinishing).isFalse()
            }
        }
    }

    /** Every control of a row and every switch ends inside a 360 dp screen, and the signal label is on it. */
    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `phone portrait keeps the switches and the signal labels inside the screen`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, list ->
                val screenWidth = a.resources.displayMetrics.widthPixels
                assertThat(screenWidth).isEqualTo(720) // 360 dp at xhdpi: the qualifier took
                val views =
                    listOf(
                        R.id.show_names,
                        R.id.show_fps,
                        R.id.quality_main,
                        R.id.layout_spinner,
                        R.id.change_code,
                    ).map { a.findViewById<View>(it) } +
                        (0 until list.childCount).flatMap { i ->
                            listOf(R.id.drag_handle, R.id.check, R.id.name, R.id.signal)
                                .map { list.getChildAt(i).findViewById<View>(it) }
                        }
                views.forEach { view ->
                    val where = IntArray(2).also(view::getLocationOnScreen)
                    val name = a.resources.getResourceEntryName(view.id)
                    assertWithMessage("$name fits").that(where[0] + view.width).isAtMost(screenWidth)
                    assertWithMessage("$name is visible").that(view.width).isGreaterThan(0)
                    assertThat(where[0]).isAtLeast(0)
                }
                // The switch's thumb is its right end: the whole widget must be on screen, not just its text.
                val row = list.getChildAt(0)
                val signal = row.findViewById<TextView>(R.id.signal)
                assertThat(signal.text.toString()).isEqualTo("signal")
                assertThat(signal.layout.getEllipsisCount(0)).isEqualTo(0)
                val fourth = list.getChildAt(3).findViewById<TextView>(R.id.signal)
                assertThat(fourth.text.toString()).isEqualTo("no signal")
            }
        }
    }
}
