package com.itsluminous.hikviewer.ui.grid

import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.VectorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.player.TileLiveness
import com.itsluminous.hikviewer.player.TileState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class CameraTileViewTest {
    // Themed like an Activity's context: the application context carries no theme under Robolectric,
    // and the detail dialog is an AppCompat one.
    private val context: android.content.Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_HikViewer)
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
    fun `the connecting spinner is an 8 fps frame animation, not the platform's 60 fps vector animation`() {
        // The platform's indeterminate drawable redraws on every vsync; eight steps a second is
        // enough for "not yet" and costs the main thread an eighth of the frames while tiles connect.
        val drawable = tile.findViewById<ProgressBar>(R.id.spinner).indeterminateDrawable
        assertThat(drawable).isInstanceOf(AnimationDrawable::class.java)
        val frames = drawable as AnimationDrawable
        assertThat(frames.numberOfFrames).isEqualTo(8)
        assertThat((0 until frames.numberOfFrames).sumOf { frames.getDuration(it) }).isEqualTo(1000)
        assertThat(frames.isOneShot).isFalse()
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
        tile.bind(TileState.Live)
        assertThat(overlay.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `the retry control is an icon-only transparent button, 48 dp, with no text anywhere but the reason`() {
        tile.bind(TileState.Error("No video"))
        val retry = tile.findViewById<ImageButton>(R.id.retry)
        assertThat(retry.drawable).isInstanceOf(VectorDrawable::class.java)
        assertThat(shadowOf(retry.drawable).createdFromResId).isEqualTo(R.drawable.ic_retry)
        assertThat(retry.background).isNull()
        assertThat(retry.contentDescription.toString()).isEqualTo(context.getString(R.string.tile_retry))
        val size = context.resources.getDimensionPixelSize(R.dimen.tile_retry_icon)
        assertThat(retry.layoutParams.width to retry.layoutParams.height).isEqualTo(size to size)
        assertThat(size).isEqualTo((48 * context.resources.displayMetrics.density).toInt())
        // The only text on the overlay is the reason; the button itself is a glyph.
        val texts = overlay.descendants().filterIsInstance<TextView>()
        assertThat(texts).containsExactly(tile.findViewById<TextView>(R.id.error_message))
        assertThat(overlay.descendants().filterIsInstance<Button>()).isEmpty()
    }

    @Test
    fun `the retry icon is focusable, grows a tenth with focus and its click is the tile's click`() {
        val grown = CameraTileView.RETRY_FOCUS_SCALE to CameraTileView.RETRY_FOCUS_SCALE
        tile.bind(TileState.Error("No video"))
        val retry = tile.findViewById<ImageButton>(R.id.retry)
        assertThat(retry.isFocusable).isTrue()
        assertThat(retry.isFocusableInTouchMode).isFalse()
        assertThat(retry.scaleX to retry.scaleY).isEqualTo(1f to 1f)
        // The tile takes focus before its descendants, so the D-pad lands on the tile, not the
        // icon; the icon grows all the same, which is how a D-pad user sees that OK will retry.
        tile.isFocusableInTouchMode = true
        assertThat(tile.requestFocus()).isTrue()
        assertThat(tile.isFocused).isTrue()
        assertThat(retry.isFocused).isFalse()
        assertThat(retry.scaleX to retry.scaleY).isEqualTo(grown)
        retry.isFocusableInTouchMode = true
        assertThat(retry.requestFocus()).isTrue()
        assertThat(retry.scaleX to retry.scaleY).isEqualTo(grown)
        tile.clearFocus()
        assertThat(retry.scaleX to retry.scaleY).isEqualTo(1f to 1f)
        // A focused tile whose stream comes back loses the scale with the overlay, and gets it
        // back with the next error.
        tile.requestFocus()
        tile.bind(TileState.Live)
        assertThat(retry.scaleX to retry.scaleY).isEqualTo(1f to 1f)
        tile.bind(TileState.Error("No video"))
        assertThat(retry.scaleX to retry.scaleY).isEqualTo(grown)
        // Clicking the icon is clicking the tile: the grid's one click path reopens the stream.
        var tileClicks = 0
        tile.setOnClickListener { tileClicks++ }
        assertThat(retry.performClick()).isTrue()
        assertThat(tileClicks).isEqualTo(1)
    }

    /** Round 18: a non-technical user on a TV we cannot inspect photographs the codec's words from this dialog. */
    @Test
    fun `a long press on the retry icon of a decoder error opens the selectable detail dialog`() {
        val detail = "codec=c2.amlogic.avc.decoder error=-38 transient=false recoverable=false diag=error_neg_38"
        tile.bind(TileState.Error("Decoder error", detail = detail))
        assertThat(tile.errorDetail).isEqualTo(detail)
        val retry = tile.findViewById<ImageButton>(R.id.retry)
        assertThat(retry.isLongClickable).isTrue()
        assertThat(retry.performLongClick()).isTrue()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(dialog.isShowing).isTrue()
        val message = dialog.findViewById<TextView>(android.R.id.message)!!
        assertThat(message.text.toString()).isEqualTo(detail)
        assertThat(message.isTextSelectable).isTrue()
        assertThat(shadowOf(dialog).title.toString()).isEqualTo(context.getString(R.string.tile_decoder_detail_title))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        assertThat(dialog.isShowing).isFalse()
        // A stream error carries no codec detail: nothing to show, and the long press falls through.
        tile.bind(TileState.Error("No video"))
        assertThat(tile.errorDetail).isNull()
        assertThat(tile.showDecoderDetail()).isFalse()
        tile.bind(TileState.Live)
        assertThat(tile.errorDetail).isNull()
        assertThat(ShadowDialog.getLatestDialog()).isSameInstanceAs(dialog) // no new dialog was opened
    }

    private fun View.descendants(): List<View> =
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).descendants() } + this else listOf(this)

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
    fun `a keyframe tile's dot is a lighter green than a live tile's`() {
        val dot = tile.findViewById<View>(R.id.live_dot)
        tile.liveness = TileLiveness.LIVE
        val live = dot.backgroundTintList!!.defaultColor
        tile.liveness = TileLiveness.AT_ONE_FPS
        val oneFps = dot.backgroundTintList!!.defaultColor
        tile.liveness = TileLiveness.NOT_LIVE
        val stalled = dot.backgroundTintList!!.defaultColor
        assertThat(live).isEqualTo(context.getColor(R.color.live_dot_flowing))
        assertThat(oneFps).isEqualTo(context.getColor(R.color.live_dot_one_fps))
        assertThat(stalled).isEqualTo(context.getColor(R.color.live_dot_stalled))
        // Lighter: every channel of the 1 fps green is at least as bright as the live green's.
        assertThat(android.graphics.Color.red(oneFps)).isAtLeast(android.graphics.Color.red(live))
        assertThat(android.graphics.Color.green(oneFps)).isAtLeast(android.graphics.Color.green(live))
        assertThat(android.graphics.Color.blue(oneFps)).isAtLeast(android.graphics.Color.blue(live))
        // Both greens count as flowing for the status text's purposes.
        tile.liveness = TileLiveness.AT_ONE_FPS
        assertThat(tile.framesFlowing).isTrue()
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
    fun `hiding the focus visual drops the border but not the focus, and lifting still draws`() {
        val frame = tile.findViewById<TileFocusOverlay>(R.id.focus_frame)
        tile.isFocusableInTouchMode = true
        assertThat(tile.requestFocus()).isTrue()
        assertThat(tile.focusVisualShown).isTrue()
        tile.focusVisualShown = false
        assertThat(tile.isFocused).isTrue()
        assertThat(tile.hasFocus()).isTrue()
        assertThat(tile.focusBorderVisible).isFalse()
        assertThat(frame.borderWidth).isEqualTo(0f)
        tile.lifted = true
        assertThat(tile.focusBorderVisible).isTrue()
        tile.lifted = false
        tile.focusVisualShown = true
        assertThat(tile.focusBorderVisible).isTrue()
        assertThat(frame.borderWidth).isEqualTo(context.resources.getDimension(R.dimen.tile_focus_border))
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
