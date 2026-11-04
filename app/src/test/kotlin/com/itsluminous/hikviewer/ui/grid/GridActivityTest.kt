package com.itsluminous.hikviewer.ui.grid

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.stream.StreamException
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

    /** Streams run on real worker threads; pumps the main looper until [condition] holds (max 5 s). */
    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in time" }
            Thread.sleep(20)
            ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun `shows one tile per camera with signal, first tile focused, top bar hidden`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                assertThat(grid.childCount).isEqualTo(12)
                assertThat(grid.shape.columns to grid.shape.rows).isEqualTo(4 to 3)
                assertThat((grid.getChildAt(0) as CameraTileView).cameraName.toString()).isEqualTo("Camera 1")
                assertThat((grid.getChildAt(0) as CameraTileView).showName).isFalse()
                assertThat((grid.getChildAt(0) as CameraTileView).showFrameRate).isFalse()
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
    fun `state updates never recreate tile views or their surfaces`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                val tiles = (0 until grid.childCount).map { grid.getChildAt(it) as CameraTileView }
                val surfaces = tiles.map { it.surfaceView }
                val vm = a.viewModels<GridViewModel>().value
                // Every kind of state churn the player layer produces: layout change, settings
                // re-apply, focus move (decoder policy), tile errors and a stream retry.
                vm.setLayout(GridLayoutMode.GRID_3X3)
                vm.setLayout(GridLayoutMode.AUTO)
                vm.applySettings()
                grid.getChildAt(4).requestFocus()
                grid.getChildAt(7).requestFocus()
                cloud.accounts.single().nextStreamError =
                    StreamException.ChannelOffline(
                        vm.playback.players.value[2]
                            .camera,
                    )
                vm.retry(2)
                awaitUi { (grid.getChildAt(2) as CameraTileView).showsRetry }
                vm.applySettings()
                ShadowLooper.idleMainLooper()
                val after = (0 until grid.childCount).map { grid.getChildAt(it) }
                assertThat(after).containsExactlyElementsIn(tiles).inOrder()
                assertThat(tiles.map { it.surfaceView }).containsExactlyElementsIn(surfaces).inOrder()
            }
        }
    }

    @Test
    fun `a failed tile is dimmed with Retry and OK on it reopens that stream`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                val vm = a.viewModels<GridViewModel>().value
                val account = cloud.accounts.single()
                account.nextStreamError =
                    StreamException.ChannelOffline(
                        vm.playback.players.value[1]
                            .camera,
                    )
                vm.retry(1)
                val tile = grid.getChildAt(1) as CameraTileView
                awaitUi { tile.showsRetry }
                assertThat(tile.findViewById<View>(R.id.error_overlay).visibility).isEqualTo(View.VISIBLE)
                assertThat(tile.findViewById<TextView>(R.id.error_message).text.toString()).isEqualTo("No video")
                val opened = account.opened.size
                tile.performClick()
                awaitUi { account.opened.size == opened + 1 && !tile.showsRetry }
                assertThat(
                    account.opened
                        .last()
                        .first.channelNo,
                ).isEqualTo(2)
                assertThat(shadowOf(a).nextStartedActivity).isNull()
            }
        }
    }

    @Test
    fun `a stream that cannot decrypt does not re-prompt while the stored code matches the recorder`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                val vm = a.viewModels<GridViewModel>().value
                val account = cloud.accounts.single()
                val listings = cloud.deviceListings
                account.nextStreamError = StreamException.WrongVerificationCode("nals=30")
                vm.retry(0)
                awaitUi { (grid.getChildAt(0) as CameraTileView).showsRetry }
                awaitUi { cloud.deviceListings == listings + 1 }
                ShadowLooper.idleMainLooper()
                assertThat(app.graph.settingsStore.verificationCode).isEqualTo(FakeCloudServices.GOOD_CODE)
                assertThat(
                    shadowOf(
                        ApplicationProvider.getApplicationContext<android.app.Application>(),
                    ).latestAlertDialog,
                ).isNull()
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
                assertThat(app.graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.HERO)
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

    private fun GridActivity.key(keyCode: Int) {
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        ShadowLooper.idleMainLooper()
    }

    private fun CameraGridLayout.names() = (0 until childCount).map {
        (childAtPosition(it) as CameraTileView).cameraName.toString()
    }

    @Test
    fun `long press opens a menu whose Move lifts the tile`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                val tile = grid.getChildAt(0) as CameraTileView
                assertThat(tile.isLongClickable).isTrue()
                assertThat(tile.performLongClick()).isTrue()
                val popup =
                    org.robolectric.shadows.ShadowPopupMenu
                        .getLatestPopupMenu()
                assertThat(popup.menu.size()).isEqualTo(TileAction.entries.size)
                assertThat(
                    popup.menu
                        .getItem(0)
                        .title
                        .toString(),
                ).isEqualTo(a.getString(R.string.tile_action_move))
                popup.menu.performIdentifierAction(0, 0)
                ShadowLooper.idleMainLooper()
                assertThat(a.isMoving).isTrue()
                assertThat(tile.lifted).isTrue()
                assertThat(a.findViewById<View>(R.id.move_hint).visibility).isEqualTo(View.VISIBLE)
            }
        }
    }

    @Test
    fun `D-pad swaps the lifted tile with its neighbour, OK drops and persists, players stay`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                awaitUi {
                    cloud.accounts
                        .single()
                        .opened.size == 12
                }
                val vm = a.viewModels<GridViewModel>().value
                val players = vm.playback.players.value
                val lifted = grid.getChildAt(0) as CameraTileView
                a.onTileAction(0, TileAction.MOVE)
                a.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                assertThat(grid.names().take(3)).containsExactly("Camera 2", "Camera 1", "Camera 3").inOrder()
                assertThat(grid.childAtPosition(1)).isSameInstanceAs(lifted) // the view moved, not its camera
                assertThat(lifted.lifted).isTrue()
                assertThat(lifted.isFocused).isTrue()
                a.key(KeyEvent.KEYCODE_DPAD_DOWN)
                assertThat(grid.childAtPosition(5)).isSameInstanceAs(lifted)
                assertThat(grid.names()[1]).isEqualTo("Camera 6")
                a.key(KeyEvent.KEYCODE_DPAD_CENTER)
                assertThat(a.isMoving).isFalse()
                assertThat(lifted.lifted).isFalse()
                assertThat(a.findViewById<View>(R.id.move_hint).visibility).isEqualTo(View.GONE)
                assertThat(shadowOf(a).nextStartedActivity).isNull() // OK dropped, it did not open fullscreen
                val serial = FakeCloudServices.SERIAL
                assertThat(
                    app.graph.arrangementStore
                        .order(GridLayoutMode.AUTO)!!
                        .take(2),
                ).containsExactly("$serial/2", "$serial/6")
                    .inOrder()
                assertThat(vm.playback.players.value).containsExactlyElementsIn(players)
                assertThat(cloud.accounts.single().opened).hasSize(12)
            }
        }
    }

    @Test
    fun `Back cancels a move and restores the arrangement, and the arrangement is per layout`() {
        val serial = FakeCloudServices.SERIAL
        app.graph.arrangementStore.setOrder(GridLayoutMode.HERO, listOf("$serial/4"))
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                val lifted = grid.getChildAt(2) as CameraTileView
                a.onTileAction(2, TileAction.MOVE)
                a.key(KeyEvent.KEYCODE_DPAD_LEFT)
                a.key(KeyEvent.KEYCODE_DPAD_LEFT)
                assertThat(grid.childAtPosition(0)).isSameInstanceAs(lifted)
                a.key(KeyEvent.KEYCODE_BACK)
                assertThat(a.isFinishing).isFalse()
                assertThat(a.isMoving).isFalse()
                assertThat(grid.names().take(3)).containsExactly("Camera 1", "Camera 2", "Camera 3").inOrder()
                assertThat(grid.childAtPosition(2)).isSameInstanceAs(lifted)
                assertThat(lifted.isFocused).isTrue()
                val vm = a.viewModels<GridViewModel>().value
                vm.setLayout(GridLayoutMode.HERO)
                ShadowLooper.idleMainLooper()
                assertThat(grid.names().take(2)).containsExactly("Camera 4", "Camera 1").inOrder()
                vm.setLayout(GridLayoutMode.AUTO)
                ShadowLooper.idleMainLooper()
                assertThat(grid.names().take(2)).containsExactly("Camera 1", "Camera 2").inOrder()
            }
        }
    }

    @Test
    fun `a pointer drag swaps the lifted tile with the tile under it and releasing drops`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.grid { a, grid ->
                val lifted = grid.childAtPosition(0) as CameraTileView
                a.onTileAction(0, TileAction.MOVE)

                fun centre(position: Int): Pair<Float, Float> {
                    val v = grid.childAtPosition(position)!!
                    val origin = IntArray(2).also(grid::getLocationOnScreen)
                    return (origin[0] + (v.left + v.right) / 2f) to (origin[1] + (v.top + v.bottom) / 2f)
                }

                fun touch(
                    action: Int,
                    position: Int,
                ) {
                    val (x, y) = centre(position)
                    val now = android.os.SystemClock.uptimeMillis()
                    a.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now, action, x, y, 0))
                    ShadowLooper.idleMainLooper()
                }
                touch(android.view.MotionEvent.ACTION_DOWN, 0)
                touch(android.view.MotionEvent.ACTION_MOVE, 2) // drag over the third tile
                assertThat(grid.childAtPosition(2)).isSameInstanceAs(lifted)
                assertThat(grid.names().take(3)).containsExactly("Camera 3", "Camera 2", "Camera 1").inOrder()
                assertThat(a.isMoving).isTrue()
                touch(android.view.MotionEvent.ACTION_UP, 2)
                assertThat(a.isMoving).isFalse()
                assertThat(lifted.lifted).isFalse()
                assertThat(shadowOf(a).nextStartedActivity).isNull() // the release was a drop, not a tap
                assertThat(
                    app.graph.arrangementStore
                        .order(GridLayoutMode.AUTO)!!
                        .first(),
                ).endsWith("/3")
            }
        }
    }

    private inline fun <reified VM : androidx.lifecycle.ViewModel> GridActivity.viewModels() =
        lazy { androidx.lifecycle.ViewModelProvider(this)[VM::class.java] }
}
