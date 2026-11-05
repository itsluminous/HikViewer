package com.itsluminous.hikviewer.diagnostics

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Printer

/**
 * Debug-only: reports every main-thread message that runs longer than [thresholdMillis], with the
 * main thread's stack as it was while the message was still running.
 *
 * Two parts. A [Printer] on the main looper sees the start and end of every message, so a slow one
 * is logged with what it was (`>>>>> Dispatching to Handler (...) {callback} what`) and how long it
 * took. A daemon thread polls every [POLL_MILLIS]: when a message has been running for longer than
 * the threshold it captures the main thread's stack, which the Printer cannot do (it only runs
 * between messages). Together they answer "what blocks the remote on the TV": the message names the
 * work, the stack names the line. Costs one `uptimeMillis()` per message, which is why this lives in
 * the debug source set and is installed by [DebugInitProvider].
 */
class MainThreadWatchdog(
    private val thresholdMillis: Long = DEFAULT_THRESHOLD_MILLIS,
) {
    @Volatile private var startedAt = 0L

    @Volatile private var current: String? = null

    @Volatile private var reported = false

    private val printer =
        Printer { line ->
            if (line.startsWith(DISPATCH_PREFIX)) {
                current = line
                reported = false
                startedAt = SystemClock.uptimeMillis()
            } else if (line.startsWith(FINISH_PREFIX)) {
                val started = startedAt
                startedAt = 0L
                val took = SystemClock.uptimeMillis() - started
                if (started != 0L && took >= thresholdMillis) {
                    Log.w(TAG, "main thread message took $took ms: ${current?.removePrefix(DISPATCH_PREFIX)}")
                }
            }
        }

    /** Starts watching; call once. */
    fun install() {
        val main = Looper.getMainLooper()
        main.setMessageLogging(printer)
        Thread({ poll(main.thread) }, "main-watchdog").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "watching the main thread for messages over $thresholdMillis ms")
    }

    private fun poll(mainThread: Thread) {
        while (true) {
            SystemClock.sleep(POLL_MILLIS)
            val started = startedAt
            if (started == 0L || reported) continue
            val running = SystemClock.uptimeMillis() - started
            if (running < thresholdMillis) continue
            reported = true
            val stack = mainThread.stackTrace.take(STACK_DEPTH).joinToString("\n    at ")
            val what = current?.removePrefix(DISPATCH_PREFIX)
            Log.w(TAG, "main thread busy for $running ms so far in $what\n    at $stack")
        }
    }

    /** Defaults. */
    companion object {
        /** The brief's bar: anything over this on the main thread is a visible hitch at 60 Hz. */
        const val DEFAULT_THRESHOLD_MILLIS: Long = 100
        private const val POLL_MILLIS = 50L
        private const val STACK_DEPTH = 24
        private const val TAG = "MainWatchdog"
        private const val DISPATCH_PREFIX = ">>>>> Dispatching to "
        private const val FINISH_PREFIX = "<<<<< Finished to "
    }
}
