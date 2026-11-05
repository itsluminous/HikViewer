package com.itsluminous.hikviewer.player

/**
 * Decides how many tiles may decode every frame ("live"); the rest show one keyframe per second.
 *
 * The static part comes from the feasibility measurements on a 4x A55, 1.8 GB TV: a software
 * H.264 decoder costs ~17 MB and ~22 % of a core per 960x480@12fps tile, 8 live tiles were safe and
 * 12 sagged into the low-memory killer. The dynamic part watches the decoders' input queues: when
 * frames pile up for [LAG_DEMOTE_MILLIS] the device cannot keep up, so the lowest-priority live tile
 * is demoted; after [HEADROOM_PROMOTE_MILLIS] without lag one tile is promoted again, never above
 * the static ceiling. Consecutive changes are at least [MIN_CHANGE_INTERVAL_MILLIS] apart in either
 * direction: every change re-creates decoders, so a limit that oscillates faster than that makes
 * tiles flicker instead of helping. A promotion that lags again within [RELAPSE_MILLIS] was
 * premature, so each relapse doubles the calm period the next promotion must wait for (up to
 * [MAX_HEADROOM_MILLIS]); a promotion that holds resets it. Pure Kotlin so the thresholds are
 * unit-testable; the caller feeds time.
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
    private var lastChangeAt: Long? = null
    private var lastPromotionAt: Long? = null

    /** Calm time currently required before promoting; grows on relapses. */
    var headroomMillis: Long = HEADROOM_PROMOTE_MILLIS
        private set

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
        val settled = lastChangeAt?.let { nowMillis - it >= MIN_CHANGE_INTERVAL_MILLIS } ?: true
        if (maxQueuedFrames > LAG_FRAMES) onLag(nowMillis, maxQueuedFrames, settled) else onCalm(nowMillis, settled)
        return liveLimit
    }

    private fun onLag(
        nowMillis: Long,
        maxQueuedFrames: Int,
        settled: Boolean,
    ) {
        calmSince = null
        val since = lagSince ?: nowMillis.also { lagSince = it }
        if (nowMillis - since >= LAG_DEMOTE_MILLIS &&
            liveLimit > MIN_LIVE &&
            settled
        ) {
            demote(nowMillis, maxQueuedFrames)
        }
    }

    private fun onCalm(
        nowMillis: Long,
        settled: Boolean,
    ) {
        lagSince = null
        val since = calmSince ?: nowMillis.also { calmSince = it }
        lastPromotionAt?.let { if (nowMillis - it >= RELAPSE_MILLIS) headroomMillis = HEADROOM_PROMOTE_MILLIS }
        if (nowMillis - since >= headroomMillis && liveLimit < ceiling && settled) promote(nowMillis)
    }

    private fun demote(
        nowMillis: Long,
        maxQueuedFrames: Int,
    ) {
        liveLimit--
        lagSince = nowMillis
        lastChangeAt = nowMillis
        val relapse = lastPromotionAt?.let { nowMillis - it < RELAPSE_MILLIS } == true
        if (relapse) headroomMillis = minOf(headroomMillis * 2, MAX_HEADROOM_MILLIS)
        lastPromotionAt = null
        val note = if (relapse) ", next promotion after ${headroomMillis / MILLIS_PER_SECOND} s calm" else ""
        onDecision("live budget: demote, queue=$maxQueuedFrames frames, limit=$liveLimit$note")
    }

    private fun promote(nowMillis: Long) {
        liveLimit++
        calmSince = nowMillis
        lastChangeAt = nowMillis
        lastPromotionAt = nowMillis
        onDecision("live budget: promote, limit=$liveLimit")
    }

    /** Thresholds. */
    companion object {
        /** More than this many frames waiting at a decoder input counts as lag. */
        const val LAG_FRAMES: Int = 6

        /** Lag must persist this long before a tile is demoted. */
        const val LAG_DEMOTE_MILLIS: Long = 2_000

        /** No lag for this long before a tile is promoted back. */
        const val HEADROOM_PROMOTE_MILLIS: Long = 15_000

        /** At least one tile (the hero, or the first) is always live. */
        const val MIN_LIVE: Int = 1

        /** Two limit changes are never closer than this; each one re-creates decoders. */
        const val MIN_CHANGE_INTERVAL_MILLIS: Long = 10_000

        /** A demotion this soon after a promotion means the promotion was premature. */
        const val RELAPSE_MILLIS: Long = 60_000

        /** Longest calm period a relapsing device is made to wait before the next promotion. */
        const val MAX_HEADROOM_MILLIS: Long = 240_000

        private const val MILLIS_PER_SECOND = 1_000L

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
