package com.itsluminous.hikviewer.ui.grid

import android.content.pm.PackageManager
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.viewModels
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

    /** 1 + 6 holds seven tiles upright; turned it is drawn as 1 + 5 and the seventh tile waits, unseen, in the arrangement. */
    @Test
    fun `turning the phone evicts the last tile of an even hero layout and turning back restores it`() {
        val serial = FakeCloudServices.SERIAL
        app.graph.settingsStore.visibleCameraKeys = (1..8).map { "$serial/$it" }
        app.graph.arrangementStore.lastLayout = GridLayoutMode.HERO_1_6
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                assertThat(grid.shape.columns).isEqualTo(2)
                assertThat(grid.shape.capacity).isEqualTo(7)
                assertThat(shownChannels(grid)).containsExactly(1, 2, 3, 4, 5, 6, 7).inOrder()
                assertThat(
                    a
                        .viewModels<GridViewModel>()
                        .value.playback.players.value,
                ).hasSize(7)
            }
            RuntimeEnvironment.setQualifiers("w640dp-h360dp-land-xhdpi")
            scenario.recreate()
            scenario.grid { a, grid ->
                assertThat(grid.shape.columns).isEqualTo(3) // the 1 + 5 grid
                assertThat(grid.shape.capacity).isEqualTo(6)
                assertThat(shownChannels(grid)).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
                assertThat(
                    a
                        .viewModels<GridViewModel>()
                        .value.playback.players.value,
                ).hasSize(6)
                // The arrangement still has every camera; the seventh is only without a cell.
                a.viewModels<GridViewModel>().value.moveTile(0, 1)
                assertThat(app.graph.arrangementStore.order(GridLayoutMode.HERO_1_6))
                    .containsExactlyElementsIn(listOf(2, 1, 3, 4, 5, 6, 7, 8).map { "$serial/$it" })
                    .inOrder()
            }
            RuntimeEnvironment.setQualifiers("w360dp-h640dp-port-xhdpi")
            scenario.recreate()
            scenario.grid { a, grid ->
                assertThat(grid.shape.capacity).isEqualTo(7)
                assertThat(shownChannels(grid)).containsExactly(2, 1, 3, 4, 5, 6, 7).inOrder()
                assertThat(
                    a
                        .viewModels<GridViewModel>()
                        .value.playback.players.value,
                ).hasSize(7)
            }
        }
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
