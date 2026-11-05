package com.itsluminous.hikviewer.ui.common

import android.view.View

/**
 * Show-on-activity, hide-after-idle behaviour for a transient visual: the grid's top bar, the
 * fullscreen "switch camera" hint and the grid's focus border share it, so all follow the same 5 s
 * policy.
 *
 * The [visual] is hidden to begin with so the cameras get the whole screen. Any key or pointer
 * movement ([onUserActivity]) shows it without moving focus, and every event restarts the
 * [autoHideMillis] timer; when the timer fires the visual hides unless it reports that it
 * [Visual.holdsFocus] (hiding a focused view would throw focus somewhere random). The scheduler is
 * injected (`View.postDelayed` in production) so the timing is unit-testable.
 *
 * The usual visual is a whole view toggled between `VISIBLE` and `INVISIBLE` (the [View]
 * constructor); the grid's focus border is a flag on its tiles instead, so a [Visual] can be
 * anything that can be shown and hidden.
 */
class InactivityTimer(
    private val visual: Visual,
    private val autoHideMillis: Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
) {
    /** Something the timer shows on activity and hides when idle. */
    interface Visual {
        /** True while shown. */
        val isShown: Boolean

        /** Makes the visual visible; called only while hidden. */
        fun show()

        /** Takes the visual off the screen. */
        fun hide()

        /** True while hiding would take focus away with it; the timer then re-arms instead. */
        val holdsFocus: Boolean get() = false
    }

    /** A view toggled between `VISIBLE` and `INVISIBLE`; it holds focus when any view in it does. */
    private class ViewVisual(
        private val target: View,
    ) : Visual {
        override val isShown: Boolean get() = target.visibility == View.VISIBLE

        override fun show() {
            target.visibility = View.VISIBLE
        }

        override fun hide() {
            target.visibility = View.INVISIBLE
        }

        override val holdsFocus: Boolean get() = target.hasFocus()
    }

    constructor(
        target: View,
        autoHideMillis: Long,
        schedule: (Runnable, Long) -> Unit = { r, ms -> target.postDelayed(r, ms) },
        cancel: (Runnable) -> Unit = { target.removeCallbacks(it) },
    ) : this(ViewVisual(target), autoHideMillis, schedule, cancel)

    private val hideRunnable = Runnable { hideIfIdle() }

    /** True while the visual is shown. */
    val isVisible: Boolean get() = visual.isShown

    init {
        visual.hide()
    }

    /** A key or pointer event happened: show the visual and restart the timer. */
    fun onUserActivity() {
        if (!isVisible) visual.show()
        restart()
    }

    /** Hides immediately (for example when leaving the screen). */
    fun hideNow() {
        cancel(hideRunnable)
        visual.hide()
    }

    private fun restart() {
        cancel(hideRunnable)
        schedule(hideRunnable, autoHideMillis)
    }

    private fun hideIfIdle() {
        if (visual.holdsFocus) {
            restart()
            return
        }
        visual.hide()
    }
}
