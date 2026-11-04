package com.itsluminous.hikviewer.ui.common

import android.view.View

/**
 * Show-on-activity, hide-after-idle behaviour for a transient overlay: the grid's top bar and
 * the fullscreen "switch camera" hint share it, so both follow the same 5 s policy.
 *
 * The [target] is invisible by default so the cameras get the whole screen. Any key or pointer
 * movement ([onUserActivity]) shows it without moving focus, and every event restarts the
 * [autoHideMillis] timer; when the timer fires the target hides unless one of its own views holds
 * focus (hiding a focused view would throw focus somewhere random). The scheduler is injected
 * (`View.postDelayed` in production) so the timing is unit-testable.
 */
class InactivityTimer(
    private val target: View,
    private val autoHideMillis: Long,
    private val schedule: (Runnable, Long) -> Unit = { r, ms -> target.postDelayed(r, ms) },
    private val cancel: (Runnable) -> Unit = { target.removeCallbacks(it) },
) {
    private val hideRunnable = Runnable { hideIfIdle() }

    /** True while the target is shown. */
    val isVisible: Boolean get() = target.visibility == View.VISIBLE

    init {
        target.visibility = View.INVISIBLE
    }

    /** A key or pointer event happened: show the target and restart the timer. */
    fun onUserActivity() {
        if (!isVisible) target.visibility = View.VISIBLE
        restart()
    }

    /** Hides immediately (for example when leaving the screen). */
    fun hideNow() {
        cancel(hideRunnable)
        target.visibility = View.INVISIBLE
    }

    private fun restart() {
        cancel(hideRunnable)
        schedule(hideRunnable, autoHideMillis)
    }

    private fun hideIfIdle() {
        if (target.hasFocus()) {
            restart()
            return
        }
        target.visibility = View.INVISIBLE
    }
}
