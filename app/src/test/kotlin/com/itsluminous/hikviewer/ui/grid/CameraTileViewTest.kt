package com.itsluminous.hikviewer.ui.grid

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.player.TileState
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraTileViewTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val tile = CameraTileView(context)
    private val spinner get() = tile.findViewById<View>(R.id.spinner)
    private val overlay get() = tile.findViewById<View>(R.id.error_overlay)

    @Test
    fun `is focusable and clickable but not in touch mode`() {
        assertThat(tile.isFocusable).isTrue()
        assertThat(tile.isClickable).isTrue()
        assertThat(tile.isFocusableInTouchMode).isFalse()
    }

    @Test
    fun `spinner while connecting, nothing while live or keyframe, dimmed retry on error`() {
        tile.cameraName = "Gate"
        assertThat(tile.findViewById<TextView>(R.id.name).text.toString()).isEqualTo("Gate")
        tile.bind(TileState.Connecting)
        assertThat(spinner.visibility).isEqualTo(View.VISIBLE)
        assertThat(overlay.visibility).isEqualTo(View.GONE)
        tile.bind(TileState.Live)
        assertThat(spinner.visibility).isEqualTo(View.GONE)
        assertThat(overlay.visibility).isEqualTo(View.GONE)
        assertThat(tile.showsRetry).isFalse()
        tile.bind(TileState.Keyframe)
        assertThat(spinner.visibility).isEqualTo(View.GONE)
        assertThat(overlay.visibility).isEqualTo(View.GONE)
        tile.bind(TileState.Error("No video"))
        assertThat(overlay.visibility).isEqualTo(View.VISIBLE)
        assertThat(tile.showsRetry).isTrue()
        assertThat(tile.findViewById<TextView>(R.id.error_message).text.toString()).isEqualTo("No video")
        assertThat(tile.findViewById<TextView>(R.id.retry).text.toString())
            .isEqualTo(context.getString(R.string.tile_retry))
        // The overlay never steals D-pad focus from the tile.
        assertThat(tile.findViewById<View>(R.id.retry).isFocusable).isFalse()
        tile.bind(TileState.Live)
        assertThat(overlay.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `the corner dot is red until frames flow and green while they do`() {
        val dot = tile.findViewById<View>(R.id.live_dot)
        assertThat(dot.visibility).isEqualTo(View.VISIBLE)
        assertThat(tile.framesFlowing).isFalse()
        assertThat(dot.backgroundTintList!!.defaultColor).isEqualTo(context.getColor(R.color.live_dot_stalled))
        tile.framesFlowing = true
        assertThat(dot.backgroundTintList!!.defaultColor).isEqualTo(context.getColor(R.color.live_dot_flowing))
        tile.framesFlowing = false
        assertThat(dot.backgroundTintList!!.defaultColor).isEqualTo(context.getColor(R.color.live_dot_stalled))
    }

    @Test
    fun `keyframe thumbnails are sized to the tile, shown in keyframe mode and hidden when live`() {
        val still = tile.findViewById<android.widget.ImageView>(R.id.still)
        assertThat(tile.stillWidth).isEqualTo(0) // not laid out: the pool decodes nothing
        tile.measure(
            View.MeasureSpec.makeMeasureSpec(1_920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_080, View.MeasureSpec.EXACTLY),
        )
        tile.layout(0, 0, 1_920, 1_080)
        assertThat(tile.stillWidth).isEqualTo(CameraTileView.STILL_MAX_WIDTH) // capped, aspect kept
        assertThat(tile.stillHeight).isEqualTo(CameraTileView.STILL_MAX_WIDTH * 1_080 / 1_920)
        tile.bind(TileState.Keyframe)
        assertThat(still.visibility).isEqualTo(View.GONE) // nothing decoded yet
        tile.onStill(IntArray(4 * 2) { 0xFF112233.toInt() }, 4, 2)
        org.robolectric.shadows.ShadowLooper
            .idleMainLooper()
        assertThat(still.visibility).isEqualTo(View.VISIBLE)
        val shown = (still.drawable as android.graphics.drawable.BitmapDrawable).bitmap
        assertThat(shown.width).isEqualTo(4)
        assertThat(shown.height).isEqualTo(2)
        // The next picture goes into the other bitmap, so the one on screen is never painted over.
        tile.onStill(IntArray(4 * 2), 4, 2)
        org.robolectric.shadows.ShadowLooper
            .idleMainLooper()
        assertThat((still.drawable as android.graphics.drawable.BitmapDrawable).bitmap).isNotSameInstanceAs(shown)
        tile.bind(TileState.Live)
        assertThat(still.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `binding states never replaces child views`() {
        val children = (0 until tile.childCount).map { tile.getChildAt(it) }
        listOf(TileState.Connecting, TileState.Live, TileState.Error("x"), TileState.Keyframe, TileState.Live)
            .forEach(tile::bind)
        assertThat((0 until tile.childCount).map { tile.getChildAt(it) }).containsExactlyElementsIn(children).inOrder()
    }

    @Test
    fun `focus shows the frame and scales it, while the tile and its surface never move`() {
        val frame = tile.findViewById<View>(R.id.focus_frame)
        val surface = tile.surfaceView
        assertThat(frame.visibility).isEqualTo(View.INVISIBLE)
        tile.isFocusableInTouchMode = true // Robolectric views start in touch mode
        assertThat(tile.requestFocus()).isTrue()
        assertThat(frame.visibility).isEqualTo(View.VISIBLE)
        org.robolectric.shadows.ShadowLooper
            .idleMainLooper(CameraTileView.FOCUS_ANIMATION_MILLIS * 2, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertThat(frame.scaleX).isEqualTo(CameraTileView.FOCUS_SCALE)
        assertThat(listOf(tile.scaleX, tile.scaleY, surface.scaleX, surface.scaleY)).containsExactly(1f, 1f, 1f, 1f)
        assertThat(listOf(tile.translationZ, surface.translationZ, surface.translationX)).containsExactly(0f, 0f, 0f)
        tile.lifted = true
        org.robolectric.shadows.ShadowLooper
            .idleMainLooper(CameraTileView.FOCUS_ANIMATION_MILLIS * 2, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertThat(frame.scaleX).isEqualTo(CameraTileView.LIFT_SCALE)
        assertThat(tile.scaleX).isEqualTo(1f)
        tile.lifted = false
        tile.clearFocus()
        assertThat(frame.visibility).isEqualTo(View.INVISIBLE)
        assertThat(tile.clipChildren).isFalse() // the frame may draw past the tile's edge
    }

    @Test
    fun `focus frame is drawn inside the tile, not in the gutter`() {
        tile.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY),
        )
        tile.layout(0, 0, 480, 270)
        val frame = tile.findViewById<View>(R.id.focus_frame)
        assertThat(listOf(frame.left, frame.top, frame.right, frame.bottom)).containsExactly(0, 0, 480, 270).inOrder()
        val gutter = context.resources.getDimensionPixelSize(R.dimen.tile_gap)
        val onePx =
            context.resources.displayMetrics.density
                .toInt()
                .coerceAtLeast(1)
        assertThat(gutter).isEqualTo(onePx)
        assertThat(context.resources.getDimensionPixelSize(R.dimen.tile_focus_border)).isGreaterThan(gutter)
    }

    @Test
    fun `name and fps overlays are hidden by default and toggle on`() {
        val name = tile.findViewById<TextView>(R.id.name)
        val fps = tile.findViewById<TextView>(R.id.fps)
        assertThat(name.visibility).isEqualTo(View.GONE)
        assertThat(fps.visibility).isEqualTo(View.GONE)
        tile.showName = true
        tile.showFrameRate = true
        tile.frameRate = 24.6f
        assertThat(name.visibility).isEqualTo(View.VISIBLE)
        assertThat(fps.visibility).isEqualTo(View.VISIBLE)
        assertThat(fps.text.toString()).isEqualTo("25 fps")
        tile.showName = false
        assertThat(name.visibility).isEqualTo(View.GONE)
    }
}
