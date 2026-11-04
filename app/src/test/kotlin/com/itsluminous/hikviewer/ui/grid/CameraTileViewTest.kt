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
    fun `binding states never replaces child views`() {
        val children = (0 until tile.childCount).map { tile.getChildAt(it) }
        listOf(TileState.Connecting, TileState.Live, TileState.Error("x"), TileState.Keyframe, TileState.Live)
            .forEach(tile::bind)
        assertThat((0 until tile.childCount).map { tile.getChildAt(it) }).containsExactlyElementsIn(children).inOrder()
    }

    @Test
    fun `focus shows the frame and scales up`() {
        val frame = tile.findViewById<View>(R.id.focus_frame)
        assertThat(frame.visibility).isEqualTo(View.INVISIBLE)
        tile.isFocusableInTouchMode = true // Robolectric views start in touch mode
        assertThat(tile.requestFocus()).isTrue()
        assertThat(frame.visibility).isEqualTo(View.VISIBLE)
        tile.clearFocus()
        assertThat(frame.visibility).isEqualTo(View.INVISIBLE)
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
