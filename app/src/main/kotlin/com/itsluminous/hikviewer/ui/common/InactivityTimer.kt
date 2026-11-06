package com.itsluminous.hikviewer.ui.common

import android.view.View

/**
 * Show-on-activity, hide-after-idle behaviour for a transient visual: the grid's top bar, the
 * fullscreen "switch camera" hint and the grid's focus border share it, so all follow the same 5 s
 * policy.
 *
 * The [visual] is hidden to begin with so the cameras get the whole screen. Any key or pointer
 * movement ([onUserActivity]) shows it without moving focus, and every event restarts the
 * [autoHideMillis] timer; when the timer fires the visual hides. A visual that [Visual.holdsFocus]
 * is first asked to [Visual.releaseFocus] to a sensible place (hiding a focused view would throw
 * focus somewhere random); one that cannot keeps showing and the timer re-arms. The scheduler is
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

        /** True while hiding would take focus away with it; see [releaseFocus]. */
        val holdsFocus: Boolean get() = false

        /**
         * Called when the timer fires while [holdsFocus]: move focus somewhere else and return
         * true, and the visual is hidden; return false (the default) to keep it shown and re-arm.
         */
        fun releaseFocus(): Boolean = false
    }

    /**
     * A view toggled between `VISIBLE` and `INVISIBLE`; it holds focus when any view in it does,
     * and gives it up through [onRelease].
     */
    private class ViewVisual(
        private val target: View,
        private val onRelease: () -> Boolean,
    ) : Visual {
        override val isShown: Boolean get() = target.visibility == View.VISIBLE

        override fun show() {
            target.visibility = View.VISIBLE
        }

        override fun hide() {
            target.visibility = View.INVISIBLE
        }

        override val holdsFocus: Boolean get() = target.hasFocus()

        override fun releaseFocus(): Boolean = onRelease()
    }

    /**
     * Times [target]'s visibility. [releaseFocus] is asked to move focus out of it when it goes
     * idle while holding focus (see [Visual.releaseFocus]); by default it never does.
     */
    constructor(
        target: View,
        autoHideMillis: Long,
        schedule: (Runnable, Long) -> Unit = { r, ms -> target.postDelayed(r, ms) },
        cancel: (Runnable) -> Unit = { target.removeCallbacks(it) },
        releaseFocus: () -> Boolean = { false },
    ) : this(ViewVisual(target, releaseFocus), autoHideMillis, schedule, cancel)

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
        if (visual.holdsFocus && !visual.releaseFocus()) {
            restart()
            return
        }
        visual.hide()
    }
}
