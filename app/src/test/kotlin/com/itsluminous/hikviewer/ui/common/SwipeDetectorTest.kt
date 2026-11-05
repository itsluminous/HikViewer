package com.itsluminous.hikviewer.ui.common

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeDetectorTest {
    @Test
    fun `a leftward fling steps forward and a rightward one back`() {
        assertThat(SwipeDetector.direction(dx = -300f, dy = 20f, velocityX = -1500f)).isEqualTo(+1)
        assertThat(SwipeDetector.direction(dx = 300f, dy = -40f, velocityX = 900f)).isEqualTo(-1)
    }

    @Test
    fun `short, slow or vertical gestures are not swipes`() {
        assertThat(SwipeDetector.direction(dx = -40f, dy = 0f, velocityX = -1500f)).isNull()
        assertThat(SwipeDetector.direction(dx = -300f, dy = 0f, velocityX = -100f)).isNull()
        assertThat(SwipeDetector.direction(dx = -300f, dy = 400f, velocityX = -1500f)).isNull()
    }

    @Test
    fun `onFling reports the step through the callback`() {
        val steps = mutableListOf<Int>()
        val detector = SwipeDetector(steps::add)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 900f, 300f, 0)
        val up = MotionEvent.obtain(0, 200, MotionEvent.ACTION_UP, 200f, 320f, 0)
        assertThat(detector.onFling(down, up, -2000f, 50f)).isTrue()
        assertThat(detector.onFling(null, up, -2000f, 50f)).isFalse()
        assertThat(steps).containsExactly(1)
        down.recycle()
        up.recycle()
    }
}
