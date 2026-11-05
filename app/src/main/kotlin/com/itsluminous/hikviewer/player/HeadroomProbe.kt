package com.itsluminous.hikviewer.player

import android.app.ActivityManager
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the device has to spare, for [LiveBudget] to decide whether a tile beyond the static
 * ceiling may decode live. Both readings are cheap and both are approximate; the budget treats
 * them as a gate (do not even try without headroom) and relies on the decoders' lag to catch
 * what the gate let through.
 *
 * "Unknown" is spelt `0`: a probe that has not measured yet, or is not running, reports no idle
 * CPU and no spare memory, and the budget then never leaves its ceiling. The one convention runs
 * through the probe, [NONE] and [LiveBudget.sample]'s defaults, so nothing can mistake "not
 * measured" for "plenty".
 */
interface HeadroomProbe {
    /** Begins sampling; idempotent. */
    fun start()

    /** Stops sampling and releases what [start] took; idempotent. */
    fun stop()

    /**
     * 0..1: the share of CPU time a lowest-priority thread gets when it asks for some, which on
     * the MiTV tracked the device's total idle share as `top` reports it (0.15-0.18 against
     * 13-19 %). Near 0 means every core is busy with work that outranks it, which on this app
     * means decoders (and the UI thread, which must not be outranked either). 0 until the first
     * burst after [start] has been measured.
     */
    val cpuIdleFraction: Float

    /** Bytes the system can still give out before it starts killing processes; 0 when unknown. */
    val spareMemoryBytes: Long

    /** Stand-ins. */
    companion object {
        /** No information: reads as no headroom, so the budget never leaves its ceiling. */
        val NONE: HeadroomProbe =
            object : HeadroomProbe {
                override fun start() = Unit

                override fun stop() = Unit

                override val cpuIdleFraction: Float get() = 0f
                override val spareMemoryBytes: Long get() = 0L
            }
    }
}

/**
 * Estimates idle CPU the only way an app may on Android 8+, where `/proc/stat` and
 * `/proc/loadavg` are closed to it: a thread at the lowest scheduler priority burns
 * [burstMillis] of CPU every [periodMillis] and measures how long that took on the wall. On an
 * idle device the two are equal (1.0); when the cores are taken by higher-priority threads (the
 * decoders run at the codec service's `ANDROID_PRIORITY_VIDEO`) the nice-19 thread waits, and
 * the ratio falls. On the MiTV with eight live tiles it read 0.15-0.18 while `top` showed
 * 13-19 % idle of the four cores, so it is read as the device's idle share. Cost: 10 ms of CPU
 * every 2 s, 0.5 % of a core, only while a grid shows more tiles than the static budget.
 *
 * The reported value is the smallest of the bursts recorded so far, over a window of [SAMPLES]:
 * a promotion is a commitment, so one busy burst in the window is enough to withhold it. Before
 * the first burst after [start] (and after [stop]) it is 0, unknown: a budget that read the
 * unfilled window as idle promoted above its ceiling without a single measurement.
 */
class CpuHeadroomProbe(
    private val periodMillis: Long = DEFAULT_PERIOD_MILLIS,
    private val burstMillis: Long = DEFAULT_BURST_MILLIS,
) {
    private val recent = IntArray(SAMPLES) { PERMILLE }

    /** Bursts recorded since the last [start]; 0 means nothing is known yet. */
    private val recorded = AtomicInteger(0)

    /** Bumped by every [start]/[stop], so a burst that was in flight across one is thrown away. */
    private val generation = AtomicInteger(0)
    private var thread: Thread? = null

    /** The recorded bursts' minimum, 0..1; 0 while nothing has been recorded since [start]. */
    val idleFraction: Float
        get() {
            val n = recorded.get()
            if (n == 0) return 0f
            var min = PERMILLE
            for (i in 0 until minOf(n, SAMPLES)) if (recent[i] < min) min = recent[i]
            return min / PERMILLE.toFloat()
        }

    /** Starts the probe thread; idempotent. */
    @Synchronized
    fun start() {
        if (thread != null) return
        recorded.set(0)
        val mine = generation.incrementAndGet()
        thread =
            Thread({ run(mine) }, "cpu-headroom").apply {
                isDaemon = true
                start()
            }
    }

    /** Stops it; idempotent. The reading is unknown again at once, whatever the thread still does. */
    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
        generation.incrementAndGet()
        recorded.set(0)
    }

    private fun run(mine: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(periodMillis)
                record(mine, burst())
            }
        } catch (_: InterruptedException) {
            // stop()
        }
    }

    /** Burns [burstMillis] of thread CPU time; returns the wall time it took. */
    private fun burst(): Long {
        val cpuStart = SystemClock.currentThreadTimeMillis()
        val wallStart = SystemClock.elapsedRealtime()
        var spin = 0L
        while (SystemClock.currentThreadTimeMillis() - cpuStart < burstMillis) {
            spin++
            if (spin % SPIN_CHECK == 0L && SystemClock.elapsedRealtime() - wallStart > MAX_BURST_WALL_MILLIS) break
        }
        return SystemClock.elapsedRealtime() - wallStart
    }

    /** Package-private for the unit test, which cannot wait for a real burst. */
    internal fun record(
        mine: Int,
        wallMillis: Long,
    ) {
        if (mine != generation.get()) return
        val ratio =
            if (wallMillis <= 0L) {
                PERMILLE
            } else {
                (burstMillis * PERMILLE / wallMillis).toInt().coerceIn(0, PERMILLE)
            }
        val n = recorded.get()
        recent[n % SAMPLES] = ratio
        recorded.set(n + 1)
    }

    /** The generation a burst must carry to be recorded now; for the unit test. */
    internal val currentGeneration: Int get() = generation.get()

    /** Tunables. */
    companion object {
        /** Between bursts. */
        const val DEFAULT_PERIOD_MILLIS: Long = 2_000

        /** CPU time per burst. */
        const val DEFAULT_BURST_MILLIS: Long = 10

        /** Bursts in the window the minimum is taken over (16 s at the default period). */
        const val SAMPLES: Int = 8

        /** A burst that has not finished after this long on the wall reads as 0 and gives up. */
        private const val MAX_BURST_WALL_MILLIS = 1_000L
        private const val SPIN_CHECK = 1_000L
        private const val PERMILLE = 1_000
    }
}

/**
 * The production probe: [CpuHeadroomProbe] for CPU, `ActivityManager.getMemoryInfo` for memory.
 * Memory is read only while the probe runs: the budget can use neither reading at the ceiling,
 * and a binder call twice a second from every screen for nothing was the alternative.
 */
class DeviceHeadroomProbe(
    private val activityManager: ActivityManager,
    private val cpu: CpuHeadroomProbe = CpuHeadroomProbe(),
) : HeadroomProbe {
    private val info = ActivityManager.MemoryInfo()

    @Volatile
    private var running = false

    override fun start() {
        running = true
        cpu.start()
    }

    override fun stop() {
        running = false
        cpu.stop()
    }

    override val cpuIdleFraction: Float get() = cpu.idleFraction

    /** `availMem − threshold`: what is left before the low-memory killer starts; 0 when the system already says it is low, or when not probing. */
    override val spareMemoryBytes: Long
        get() {
            if (!running) return 0L
            return synchronized(info) {
                activityManager.getMemoryInfo(info)
                if (info.lowMemory) 0L else (info.availMem - info.threshold).coerceAtLeast(0L)
            }
        }
}
