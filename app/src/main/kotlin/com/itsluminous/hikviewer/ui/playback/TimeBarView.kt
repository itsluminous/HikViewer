package com.itsluminous.hikviewer.ui.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.playback.RecordSegment

/**
 * A 24 h bar: recorded [segments] are filled, the playhead is a vertical line, hour ticks every
 * six hours. A tap or click seeks to that fraction of the day through [onSeek]; a drag (finger or
 * pressed mouse) reports every sample, so the playhead and label follow the pointer while the
 * ViewModel's debounce holds the reopen until the pointer rests. The bar is not a focusable view:
 * the Activity routes D-pad keys by the ViewModel's logical control, and [highlighted] draws the
 * frame that shows it is the active control.
 */
class TimeBarView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        /** Footage of the day shown. */
        var segments: List<RecordSegment> = emptyList()
            set(value) {
                field = value
                invalidate()
            }

        /** Playhead, 0f..1f of the day. */
        var positionFraction: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        /** True when LEFT/RIGHT act on the bar. */
        var highlighted: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        /** Called with the fraction of the day under the pointer: on press, on every drag sample and on release. */
        var onSeek: ((Float) -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val track = Paint().apply { color = ContextCompat.getColor(context, R.color.time_bar_track) }
        private val recorded = Paint().apply { color = ContextCompat.getColor(context, R.color.time_bar_recorded) }
        private val event = Paint().apply { color = ContextCompat.getColor(context, R.color.time_bar_event) }
        private val tick = Paint().apply { color = ContextCompat.getColor(context, R.color.time_bar_tick) }
        private val playhead =
            Paint().apply {
                color = ContextCompat.getColor(context, R.color.accent)
                strokeWidth = 2 * density
            }
        private val frame =
            Paint().apply {
                color = ContextCompat.getColor(context, R.color.accent)
                style = Paint.Style.STROKE
                strokeWidth = 2 * density
            }

        init {
            isClickable = true
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val inset = frame.strokeWidth
            val top = inset * 2
            val bottom = h - inset * 2
            canvas.drawRect(inset, top, w - inset, bottom, track)
            for (segment in segments) {
                val from = inset + PlaybackTimeline.fractionOfDay(segment.begin) * (w - 2 * inset)
                val toFraction =
                    if (segment.end.toLocalDate().isAfter(
                            segment.begin.toLocalDate(),
                        )
                    ) {
                        1f
                    } else {
                        PlaybackTimeline.fractionOfDay(segment.end)
                    }
                val to = inset + toFraction * (w - 2 * inset)
                val paint = if (segment.type == RecordSegment.TYPE_EVENT) event else recorded
                canvas.drawRect(from, top, maxOf(to, from + density), bottom, paint)
            }
            for (hour in HOUR_TICKS) {
                val x = inset + hour / HOURS_PER_DAY * (w - 2 * inset)
                canvas.drawRect(x - density / 2, top, x + density / 2, bottom, tick)
            }
            val x = inset + positionFraction * (w - 2 * inset)
            canvas.drawLine(x, 0f, x, h, playhead)
            if (highlighted) canvas.drawRect(inset / 2, inset / 2, w - inset / 2, h - inset / 2, frame)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (width <= 0) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> onSeek?.invoke(fractionAt(event.x))
                MotionEvent.ACTION_UP -> {
                    onSeek?.invoke(fractionAt(event.x))
                    performClick()
                }
                else -> return super.onTouchEvent(event)
            }
            return true
        }

        private fun fractionAt(x: Float): Float = (x / width).coerceIn(0f, 1f)

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private companion object {
            val HOUR_TICKS = listOf(6f, 12f, 18f)
            const val HOURS_PER_DAY = 24f
        }
    }
