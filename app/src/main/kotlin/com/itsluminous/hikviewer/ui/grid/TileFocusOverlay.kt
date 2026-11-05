package com.itsluminous.hikviewer.ui.grid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.itsluminous.hikviewer.R

/**
 * The focus border of a camera tile, drawn as the tile's last child so it sits above the
 * `SurfaceView` in the window. The 4 dp accent stroke is inset so it lies entirely inside the
 * tile: the 1 dp gutter between tiles belongs to the neighbours' hole punches, and anything drawn
 * there is wiped by whichever `SurfaceView` draws later. A [lifted] tile (being moved) gets a
 * thicker stroke and a translucent accent wash so it reads as picked up from the couch.
 *
 * The view owns no transform: no scale, no elevation. The round-4 frame grew 6 % past the tile
 * with a shadow, which needed `clipChildren = false` on the tile, and on Android 10 an unclipped
 * `SurfaceView` punches its hole with `drawColor(CLEAR)` over the whole grid (see [CameraTileView]).
 * Everything here redraws from [focused] and [lifted] alone, so a layout pass or an animation
 * elsewhere cannot leave the border half drawn.
 */
class TileFocusOverlay
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val strokeWidth = resources.getDimension(R.dimen.tile_focus_border)
        private val accent = ContextCompat.getColor(context, R.color.accent)
        private val stroke =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = accent
            }
        private val wash =
            Paint().apply {
                style = Paint.Style.FILL
                color = accent
                alpha = LIFT_WASH_ALPHA
            }
        private val bounds = RectF()

        /** True while the tile has focus: the border is drawn. */
        var focused: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        /** True while the tile is being moved: thicker border plus the wash. */
        var lifted: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        /** True when the border is drawn on the next frame: focused or lifted. */
        val borderVisible: Boolean get() = focused || lifted

        /** The stroke's current width in pixels (0 when nothing is drawn). */
        val borderWidth: Float
            get() =
                when {
                    lifted -> strokeWidth * LIFT_STROKE_FACTOR
                    focused -> strokeWidth
                    else -> 0f
                }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!borderVisible) return
            if (lifted) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wash)
            val w = borderWidth
            stroke.strokeWidth = w
            // Half the stroke straddles the path, so inset by half to keep every pixel inside the view.
            val half = w / 2f
            bounds.set(half, half, width - half, height - half)
            canvas.drawRect(bounds, stroke)
        }

        /** Lift visuals. */
        companion object {
            /** A lifted tile's border is this much thicker than a focused one's. */
            const val LIFT_STROKE_FACTOR: Float = 1.5f

            /** Alpha (0..255) of the accent wash over a lifted tile. */
            const val LIFT_WASH_ALPHA: Int = 0x33
        }
    }
