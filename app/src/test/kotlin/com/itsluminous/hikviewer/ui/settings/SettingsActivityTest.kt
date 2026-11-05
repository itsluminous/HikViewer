package com.itsluminous.hikviewer.ui.settings

import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import com.itsluminous.hikviewer.ui.login.LoginActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
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

    private fun ActivityScenario<SettingsActivity>.rows(block: (SettingsActivity, LinearLayout) -> Unit) {
        ShadowLooper.idleMainLooper()
        onActivity { a -> block(a, a.findViewById(R.id.camera_list)) }
    }

    private fun LinearLayout.handle(i: Int): View = getChildAt(i).findViewById(R.id.handle)

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

    @Test
    fun `layout spinner, quality and overlay switches write the store`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, _ ->
                // Three visible cameras: the spinner offers AUTO and 3x3 only (same filter as the grid menu).
                val layoutSpinner = a.findViewById<Spinner>(R.id.layout_spinner)
                assertThat(layoutSpinner.adapter.count).isEqualTo(2)
                layoutSpinner.setSelection(1)
                a.findViewById<SwitchCompat>(R.id.quality_main).isChecked = true
                assertThat(a.findViewById<SwitchCompat>(R.id.show_names).isChecked).isFalse()
                assertThat(a.findViewById<SwitchCompat>(R.id.show_fps).isChecked).isFalse()
                a.findViewById<SwitchCompat>(R.id.show_names).isChecked = true
                a.findViewById<SwitchCompat>(R.id.show_fps).isChecked = true
                ShadowLooper.idleMainLooper()
                assertThat(app.graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.GRID_3X3)
                assertThat(app.graph.settingsStore.fullscreenQuality).isEqualTo(StreamQuality.MAIN)
                assertThat(app.graph.settingsStore.showCameraNames).isTrue()
                assertThat(app.graph.settingsStore.showFrameRate).isTrue()
                // No hero setting: the hero is position 0 of a 1 + N layout, chosen on the grid.
                assertThat(a.findViewById<View?>(R.id.layout_spinner)).isNotNull()
                GridLayoutMode.heroModes.forEach { mode -> assertThat(app.graph.arrangementStore.order(mode)).isNull() }
            }
        }
    }

    @Test
    fun `ticking cameras updates the layout spinner on the same screen`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { a, list ->
                list.getChildAt(0).findViewById<CheckBox>(R.id.check).performClick()
                assertThat(
                    app.graph.settingsStore.visibleCameraKeys,
                ).containsExactly("$serial/2", "$serial/3").inOrder()
                // Three cameras offered AUTO and 3x3; with all five ticked the 1 + 5 layout and 4x3 fit too.
                list.getChildAt(0).findViewById<CheckBox>(R.id.check).performClick()
                list.getChildAt(3).findViewById<CheckBox>(R.id.check).performClick()
                list.getChildAt(4).findViewById<CheckBox>(R.id.check).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(a.findViewById<Spinner>(R.id.layout_spinner).adapter.count)
                    .isEqualTo(GridLayoutMode.available(5).size)
            }
        }
    }

    @Test
    fun `OK picks up a row, DOWN moves it, OK drops and saves the order`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.rows { _, list ->
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
                // UP/DOWN on a row that is not picked up is ordinary navigation.
                assertThat(list.handle(0).dispatchKeyEvent(down)).isFalse()
            }
        }
    }

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
}
