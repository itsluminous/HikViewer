package com.itsluminous.hikviewer.ui.grid

import android.view.View

/**
 * Show-on-activity, hide-after-idle behaviour of the grid's top bar.
 *
 * The bar is invisible by default so the cameras get the whole screen. Any key or pointer
 * movement ([onUserActivity]) shows it without moving focus, and every event restarts the
 * [autoHideMillis] timer; when the timer fires the bar hides unless one of its own buttons holds
 * focus (hiding a focused view would throw focus somewhere random). The scheduler is injected
 * (`View.postDelayed` in production) so the timing is unit-testable.
 */
class TopBarController(
    private val bar: View,
    private val autoHideMillis: Long,
    private val schedule: (Runnable, Long) -> Unit = { r, ms -> bar.postDelayed(r, ms) },
    private val cancel: (Runnable) -> Unit = { bar.removeCallbacks(it) },
) {
    private val hideRunnable = Runnable { hideIfIdle() }

    /** True while the bar is shown. */
    val isVisible: Boolean get() = bar.visibility == View.VISIBLE

    init {
        bar.visibility = View.INVISIBLE
    }

    /** A key or pointer event happened: show the bar and restart the timer. */
    fun onUserActivity() {
        if (!isVisible) bar.visibility = View.VISIBLE
        restart()
    }

    /** Hides immediately (for example when leaving the screen). */
    fun hideNow() {
        cancel(hideRunnable)
        bar.visibility = View.INVISIBLE
    }

    private fun restart() {
        cancel(hideRunnable)
        schedule(hideRunnable, autoHideMillis)
    }

    private fun hideIfIdle() {
        if (bar.hasFocus()) {
            restart()
            return
        }
        bar.visibility = View.INVISIBLE
    }
}
