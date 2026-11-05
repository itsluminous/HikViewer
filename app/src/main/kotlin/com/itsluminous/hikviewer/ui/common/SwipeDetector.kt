package com.itsluminous.hikviewer.ui.common

import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Turns a horizontal fling into a camera step for `GestureDetector`: a swipe to the left (finger
 * moving left, the next camera sliding in from the right) calls [onStep] with +1, a swipe to the
 * right with -1. Mostly-vertical or short, slow flings are ignored so a tap on a control or a
 * scroll never switches camera. The thresholds are in pixels and pixels per second, deliberately
 * loose: this runs on phones of every density.
 */
class SwipeDetector(
    private val onStep: (Int) -> Unit,
) : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent): Boolean = true

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float,
    ): Boolean {
        val start = e1 ?: return false
        val dx = e2.x - start.x
        val dy = e2.y - start.y
        val direction = direction(dx, dy, velocityX) ?: return false
        onStep(direction)
        return true
    }

    /** Pure decision for tests: +1, -1 or null for "not a horizontal swipe". */
    companion object {
        /** Minimum horizontal travel of a swipe. */
        const val MIN_DISTANCE_PX: Float = 80f

        /** Minimum horizontal speed of a swipe. */
        const val MIN_VELOCITY_PX_PER_S: Float = 300f

        /** The camera step a gesture of [dx], [dy] and [velocityX] means, or null when it is not a swipe. */
        fun direction(
            dx: Float,
            dy: Float,
            velocityX: Float,
        ): Int? {
            if (abs(dx) < MIN_DISTANCE_PX || abs(dx) <= abs(dy) || abs(velocityX) < MIN_VELOCITY_PX_PER_S) return null
            return if (dx < 0) +1 else -1
        }
    }
}
