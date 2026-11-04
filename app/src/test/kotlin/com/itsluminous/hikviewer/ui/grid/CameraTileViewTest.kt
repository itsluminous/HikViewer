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
    private val badge get() = tile.findViewById<TextView>(R.id.badge)

    @Test
    fun `is focusable and clickable but not in touch mode`() {
        assertThat(tile.isFocusable).isTrue()
        assertThat(tile.isClickable).isTrue()
        assertThat(tile.isFocusableInTouchMode).isFalse()
    }

    @Test
    fun `badge follows the state`() {
        tile.cameraName = "Gate"
        assertThat(tile.findViewById<TextView>(R.id.name).text.toString()).isEqualTo("Gate")
        tile.bind(TileState.Connecting)
        assertThat(badge.text.toString()).isEqualTo(context.getString(R.string.tile_connecting))
        tile.bind(TileState.Live)
        assertThat(badge.text.toString()).isEqualTo(context.getString(R.string.tile_live))
        assertThat(badge.currentTextColor).isEqualTo(context.getColor(R.color.badge_live))
        tile.bind(TileState.Keyframe)
        assertThat(badge.text.toString()).isEqualTo(context.getString(R.string.tile_keyframe))
        tile.bind(TileState.Error("No video"))
        assertThat(badge.text.toString()).isEqualTo("No video")
        assertThat(badge.currentTextColor).isEqualTo(context.getColor(R.color.badge_error))
        assertThat(tile.surfaceView.alpha).isEqualTo(0f)
        tile.bind(TileState.Live)
        assertThat(tile.surfaceView.alpha).isEqualTo(1f)
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
}
