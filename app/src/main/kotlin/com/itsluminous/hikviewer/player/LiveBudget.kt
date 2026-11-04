package com.itsluminous.hikviewer.player

/**
 * Decides how many tiles may decode every frame ("live"); the rest show one keyframe per second.
 *
 * The static part comes from the feasibility measurements on a 4x A55, 1.8 GB TV: a software
 * H.264 decoder costs ~17 MB and ~22 % of a core per 960x480@12fps tile, 8 live tiles were safe and
 * 12 sagged into the low-memory killer. The dynamic part watches the decoders' input queues: when
 * frames pile up for [LAG_DEMOTE_MILLIS] the device cannot keep up, so the lowest-priority live tile
 * is demoted; after [HEADROOM_PROMOTE_MILLIS] without lag one tile is promoted again, never above
 * the static ceiling. Pure Kotlin so the thresholds are unit-testable; the caller feeds time.
 */
class LiveBudget(
    capabilities: DeviceCapabilities,
    /** Receives one line per decision, for the INFO log. */
    private val onDecision: (String) -> Unit = {},
) {
    /** Upper bound from the hardware facts alone. */
    val ceiling: Int = staticCeiling(capabilities)

    /** Tiles currently allowed to be live. */
    var liveLimit: Int = ceiling
        private set

    private var lagSince: Long? = null
    private var calmSince: Long? = null

    init {
        onDecision(
            "live budget: ceiling=$ceiling (lowRam=${capabilities.isLowRamDevice}, " +
                "ram=${capabilities.totalMemoryMb} MB, cores=${capabilities.cpuCores})",
        )
    }

    /**
     * Feeds one measurement: [maxQueuedFrames] is the deepest input queue among live decoders.
     * Returns the limit in force after this sample.
     */
    fun sample(
        nowMillis: Long,
        maxQueuedFrames: Int,
    ): Int {
        if (maxQueuedFrames > LAG_FRAMES) {
            calmSince = null
            val since = lagSince ?: nowMillis.also { lagSince = it }
            if (nowMillis - since >= LAG_DEMOTE_MILLIS && liveLimit > MIN_LIVE) {
                liveLimit--
                lagSince = nowMillis
                onDecision("live budget: demote, queue=$maxQueuedFrames frames, limit=$liveLimit")
            }
        } else {
            lagSince = null
            val since = calmSince ?: nowMillis.also { calmSince = it }
            if (nowMillis - since >= HEADROOM_PROMOTE_MILLIS && liveLimit < ceiling) {
                liveLimit++
                calmSince = nowMillis
                onDecision("live budget: promote, limit=$liveLimit")
            }
        }
        return liveLimit
    }

    /** Thresholds. */
    companion object {
        /** More than this many frames waiting at a decoder input counts as lag. */
        const val LAG_FRAMES: Int = 6

        /** Lag must persist this long before a tile is demoted. */
        const val LAG_DEMOTE_MILLIS: Long = 2_000

        /** No lag for this long before a tile is promoted back. */
        const val HEADROOM_PROMOTE_MILLIS: Long = 15_000

        /** The focused tile is always live. */
        const val MIN_LIVE: Int = 1

        private const val MB = 1024L * 1024L
        private const val LOW_RAM_CEILING = 4
        private const val MID_RAM_CEILING = 8
        private const val HIGH_RAM_CEILING = 16
        private const val MID_RAM_BYTES = 1_400L * MB
        private const val HIGH_RAM_BYTES = 2_800L * MB
        private const val FEW_CORES = 2

        /** Static live-tile ceiling for [c]. */
        fun staticCeiling(c: DeviceCapabilities): Int {
            val byRam =
                when {
                    c.isLowRamDevice || c.totalMemoryBytes < MID_RAM_BYTES -> LOW_RAM_CEILING
                    c.totalMemoryBytes < HIGH_RAM_BYTES -> MID_RAM_CEILING
                    else -> HIGH_RAM_CEILING
                }
            return if (c.cpuCores <= FEW_CORES) minOf(byRam, LOW_RAM_CEILING) else byRam
        }
    }
}
