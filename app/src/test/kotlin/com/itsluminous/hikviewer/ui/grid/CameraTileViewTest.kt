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
    fun `focus draws the border and lifting thickens it, while the tile and its surface never move`() {
        val frame = tile.findViewById<TileFocusOverlay>(R.id.focus_frame)
        val surface = tile.surfaceView
        assertThat(tile.focusBorderVisible).isFalse()
        assertThat(frame.borderWidth).isEqualTo(0f)
        tile.isFocusableInTouchMode = true // Robolectric views start in touch mode
        assertThat(tile.requestFocus()).isTrue()
        assertThat(tile.focusBorderVisible).isTrue()
        val border = context.resources.getDimension(R.dimen.tile_focus_border)
        assertThat(frame.borderWidth).isEqualTo(border)
        tile.lifted = true
        assertThat(frame.borderWidth).isEqualTo(border * TileFocusOverlay.LIFT_STROKE_FACTOR)
        tile.clearFocus()
        assertThat(tile.focusBorderVisible).isTrue() // still lifted
        tile.lifted = false
        assertThat(tile.focusBorderVisible).isFalse()
        // Nothing is transformed: the surface's position is sacred (see the class KDoc).
        assertThat(listOf(tile.scaleX, tile.scaleY, surface.scaleX, surface.scaleY, frame.scaleX)).containsExactly(
            1f,
            1f,
            1f,
            1f,
            1f,
        )
        assertThat(listOf(tile.translationZ, surface.translationZ, surface.translationX, frame.translationZ))
            .containsExactly(0f, 0f, 0f, 0f)
    }

    @Test
    fun `the tile clips its children so a surface's hole punch stays inside it`() {
        // Android 10 clears the whole clip for an unclipped SurfaceView; see the class KDoc.
        assertThat(tile.clipChildren).isTrue()
    }

    @Test
    fun `focus overlay is the last child, fills the tile and draws its stroke inside the tile`() {
        tile.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY),
        )
        tile.layout(0, 0, 480, 270)
        val frame = tile.findViewById<TileFocusOverlay>(R.id.focus_frame)
        assertThat(tile.getChildAt(tile.childCount - 1)).isSameInstanceAs(frame)
        assertThat(tile.indexOfChild(tile.surfaceView)).isEqualTo(0)
        assertThat(listOf(frame.left, frame.top, frame.right, frame.bottom)).containsExactly(0, 0, 480, 270).inOrder()
        val gutter = context.resources.getDimensionPixelSize(R.dimen.tile_gap)
        val onePx =
            context.resources.displayMetrics.density
                .toInt()
                .coerceAtLeast(1)
        assertThat(gutter).isEqualTo(onePx)
        assertThat(context.resources.getDimensionPixelSize(R.dimen.tile_focus_border)).isGreaterThan(gutter)
        // The stroke is painted on a Canvas: every pixel must land within the overlay's bounds.
        tile.isFocusableInTouchMode = true
        tile.requestFocus()
        val canvas = RecordingCanvas()
        frame.draw(canvas)
        val rect = canvas.strokedRects.single()
        val half = frame.borderWidth / 2f
        assertThat(rect.left).isWithin(0.01f).of(half)
        assertThat(rect.top).isWithin(0.01f).of(half)
        assertThat(rect.right).isWithin(0.01f).of(480 - half)
        assertThat(rect.bottom).isWithin(0.01f).of(270 - half)
        assertThat(rect.left - half).isAtLeast(0f)
    }

    @Test
    fun `the live dot sits in the top-right corner above the surface`() {
        tile.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY),
        )
        tile.layout(0, 0, 480, 270)
        val dot = tile.findViewById<View>(R.id.live_dot)
        val margin = context.resources.getDimensionPixelSize(R.dimen.live_dot_margin)
        val size = context.resources.getDimensionPixelSize(R.dimen.live_dot_size)
        assertThat(dot.visibility).isEqualTo(View.VISIBLE)
        assertThat(dot.top).isEqualTo(margin)
        assertThat(dot.right).isEqualTo(480 - margin)
        assertThat(dot.width).isEqualTo(size)
        assertThat(dot.height).isEqualTo(size)
        assertThat(tile.indexOfChild(dot)).isGreaterThan(tile.indexOfChild(tile.surfaceView))
        // The fps counter shares the corner and stands to the dot's left.
        tile.showFrameRate = true
        tile.frameRate = 12f
        tile.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY),
        )
        tile.layout(0, 0, 480, 270)
        assertThat(tile.findViewById<View>(R.id.fps).right).isAtMost(dot.left)
    }

    /** A canvas that records stroked rectangles, for asserting where a border was painted. */
    private class RecordingCanvas : android.graphics.Canvas() {
        val strokedRects = mutableListOf<android.graphics.RectF>()

        override fun drawRect(
            rect: android.graphics.RectF,
            paint: android.graphics.Paint,
        ) {
            if (paint.style == android.graphics.Paint.Style.STROKE) strokedRects += android.graphics.RectF(rect)
        }
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
