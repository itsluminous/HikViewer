package com.itsluminous.hikviewer.ui.grid

import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopBarControllerTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val bar = FrameLayout(context)
    private val button =
        Button(context).also {
            it.isFocusableInTouchMode = true
            bar.addView(it)
        }

    /** A manual scheduler standing in for `postDelayed`. */
    private var scheduled: Pair<Runnable, Long>? = null
    private val controller =
        TopBarController(
            bar,
            autoHideMillis = 5_000,
            schedule = { r, ms -> scheduled = r to ms },
            cancel = { scheduled = null },
        )

    private fun fire() {
        val (r, _) = checkNotNull(scheduled)
        scheduled = null
        r.run()
    }

    @Test
    fun `hidden by default`() {
        assertThat(controller.isVisible).isFalse()
        assertThat(bar.visibility).isEqualTo(View.INVISIBLE)
        assertThat(scheduled).isNull()
    }

    @Test
    fun `activity shows the bar and arms a 5 s timer`() {
        controller.onUserActivity()
        assertThat(controller.isVisible).isTrue()
        assertThat(scheduled?.second).isEqualTo(5_000)
        fire()
        assertThat(controller.isVisible).isFalse()
    }

    @Test
    fun `every event restarts the timer`() {
        controller.onUserActivity()
        val first = scheduled
        controller.onUserActivity()
        assertThat(scheduled).isNotSameInstanceAs(first)
        assertThat(controller.isVisible).isTrue()
    }

    @Test
    fun `bar stays while one of its buttons has focus, hides once focus leaves`() {
        controller.onUserActivity()
        assertThat(button.requestFocus()).isTrue()
        assertThat(bar.hasFocus()).isTrue()
        fire()
        assertThat(controller.isVisible).isTrue()
        assertThat(scheduled).isNotNull()
        button.clearFocus()
        fire()
        assertThat(controller.isVisible).isFalse()
    }

    @Test
    fun `hideNow cancels the timer`() {
        controller.onUserActivity()
        controller.hideNow()
        assertThat(controller.isVisible).isFalse()
        assertThat(scheduled).isNull()
    }
}
