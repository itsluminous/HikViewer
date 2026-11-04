package com.itsluminous.hikviewer.ui.grid

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenActivity
import com.itsluminous.hikviewer.ui.settings.SettingsActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

// A 1080p television in landscape; Robolectric's default device is a portrait phone.
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class GridActivityTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud = FakeCloudServices(cameraCount = 16, signalCount = 12)
    private val decoders = RecordingDecoderFactory()

    @Before
    fun setUp() {
        app.graph = TestGraph.create(app, cloud, topBarAutoHideMillis = 1_000, decoders = decoders)
        kotlinx.coroutines.runBlocking { app.graph.account.login("a", "secret", Region.INDIA) }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
    }

    private fun ActivityScenario<GridActivity>.grid(block: (GridActivity, CameraGridLayout) -> Unit) {
        ShadowLooper.idleMainLooper()
        onActivity { a -> block(a, a.findViewById(R.id.grid)) }
    }

    @Test
    fun `shows one tile per camera with signal, first tile focused, top bar hidden`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                assertThat(grid.childCount).isEqualTo(12)
                assertThat(grid.shape.columns to grid.shape.rows).isEqualTo(4 to 3)
                assertThat((grid.getChildAt(0) as CameraTileView).cameraName.toString()).isEqualTo("Camera 1")
                assertThat(grid.getChildAt(0).isFocused).isTrue()
                assertThat(a.findViewById<View>(R.id.top_bar).visibility).isEqualTo(View.INVISIBLE)
                assertThat(a.findViewById<View>(R.id.message).visibility).isEqualTo(View.GONE)
                assertThat(cloud.accounts.single().opened).hasSize(12)
            }
        }
    }

    @Test
    fun `any key shows the top bar without moving focus and it hides after the timeout`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                val bar = a.findViewById<View>(R.id.top_bar)
                assertThat(bar.visibility).isEqualTo(View.VISIBLE)
                assertThat(bar.hasFocus()).isFalse()
                assertThat(grid.hasFocus()).isTrue()
                ShadowLooper.idleMainLooper(999, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(bar.visibility).isEqualTo(View.VISIBLE)
                ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.MILLISECONDS)
                assertThat(bar.visibility).isEqualTo(View.INVISIBLE)
            }
        }
    }

    @Test
    fun `UP from the top row focuses the layout button`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                val target = grid.focusSearch(grid.getChildAt(2), View.FOCUS_UP)
                assertThat(target?.id).isEqualTo(R.id.layout_button)
                assertThat(a.findViewById<View>(R.id.top_bar).visibility).isEqualTo(View.VISIBLE)
            }
        }
    }

    @Test
    fun `clicking a tile opens fullscreen for that index`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                grid.getChildAt(5).performClick()
                val next = shadowOf(a).nextStartedActivity
                assertThat(next.component?.className).isEqualTo(FullscreenActivity::class.java.name)
                assertThat(next.getIntExtra(FullscreenActivity.EXTRA_INDEX, -1)).isEqualTo(5)
            }
        }
    }

    @Test
    fun `settings button opens settings`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, _ ->
                a.findViewById<View>(R.id.settings_button).performClick()
                assertThat(
                    shadowOf(a).nextStartedActivity.component?.className,
                ).isEqualTo(SettingsActivity::class.java.name)
            }
        }
    }

    @Test
    fun `layout menu switches the layout`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                a.findViewById<View>(R.id.layout_button).performClick()
                ShadowLooper.idleMainLooper()
                val vm = a.viewModels<GridViewModel>().value
                vm.setLayout(GridLayoutMode.HERO)
                ShadowLooper.idleMainLooper()
                assertThat(grid.mode).isEqualTo(GridLayoutMode.HERO)
                assertThat(app.graph.settingsStore.gridLayout).isEqualTo(GridLayoutMode.HERO)
            }
        }
    }

    @Test
    fun `stop releases playback, start resumes it`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            ShadowLooper.idleMainLooper()
            assertThat(cloud.accounts.single().opened).hasSize(12)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            ShadowLooper.idleMainLooper()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ShadowLooper.idleMainLooper()
            assertThat(cloud.accounts.single().opened).hasSize(24)
        }
    }

    @Test
    fun `asks for the verification code when none is stored and accepts the right one`() {
        app.graph.settingsStore.verificationCode = null
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { _, _ ->
                val dialog =
                    shadowOf(
                        androidx.test.core.app.ApplicationProvider
                            .getApplicationContext<android.app.Application>(),
                    ).let {
                        org.robolectric.shadows.ShadowDialog
                            .getLatestDialog() as AlertDialog
                    }
                assertThat(dialog.isShowing).isTrue()
                assertThat(cloud.accounts.single().opened).isEmpty()
                val input = dialog.findViewById<android.widget.EditText>(R.id.code_input)!!
                val error = dialog.findViewById<TextView>(R.id.code_error)!!
                input.setText("wrong")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertThat(dialog.isShowing).isTrue()
                assertThat(error.visibility).isEqualTo(View.VISIBLE)
                input.setText(FakeCloudServices.GOOD_CODE)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                ShadowLooper.idleMainLooper()
                assertThat(dialog.isShowing).isFalse()
                assertThat(app.graph.settingsStore.verificationCode).isEqualTo(FakeCloudServices.GOOD_CODE)
                assertThat(cloud.accounts.single().opened).hasSize(12)
            }
        }
    }

    @Test
    fun `empty grid shows a message`() {
        app.graph.settingsStore.visibleCameraKeys = emptyList()
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                assertThat(grid.childCount).isEqualTo(0)
                val message = a.findViewById<TextView>(R.id.message)
                assertThat(message.visibility).isEqualTo(View.VISIBLE)
                assertThat(message.text.toString()).isEqualTo(a.getString(R.string.grid_empty))
            }
        }
    }

    private inline fun <reified VM : androidx.lifecycle.ViewModel> GridActivity.viewModels() =
        lazy { androidx.lifecycle.ViewModelProvider(this)[VM::class.java] }
}
