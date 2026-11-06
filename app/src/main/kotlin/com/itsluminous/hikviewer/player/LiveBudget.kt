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
 * [MAX_HEADROOM_MILLIS]); a promotion that holds resets it.
 *
 * Above the static ceiling the budget probes, up to [EXTRA_LIVE_TILES] more: the ceiling was
 * measured at 12 fps, and a recorder set to 6-8 fps (which the user plans) leaves half the CPU
 * idle while tiles sit at one still a second. A probe needs the same calm, plus the
 * [HeadroomProbe]'s say-so: idle CPU for another decoder ([IDLE_PROMOTE_FRACTION]) and memory
 * for one ([EXTRA_TILE_MEMORY_BYTES]). While
 * tiles beyond the ceiling are live, headroom that goes away (idle below [IDLE_DEMOTE_FRACTION],
 * memory below [LOW_MEMORY_BYTES]) counts as lag, so they are shed before the decoders fall behind
 * or the low-memory killer acts. Pure Kotlin so the thresholds are unit-testable; the caller feeds
 * time and the readings.
 *
 * The ceiling can be replaced by the user's choice (Settings, "Live cameras"): [setCeiling] makes
 * N the ceiling and switches probing off, since N is exactly what was asked for; the lag and
 * memory machinery keeps demoting below N and promoting back up to it. Automatic is
 * [setCeiling] with [automaticCeiling] and probing on, which is the state a new budget starts in.
 */
class LiveBudget(
    capabilities: DeviceCapabilities,
    /** Receives one line per decision, for the INFO log. */
    private val onDecision: (String) -> Unit = {},
) {
    /** The ceiling the hardware facts alone give; what "Automatic" means on this device. */
    val automaticCeiling: Int = staticCeiling(capabilities)

    /** Live tiles allowed without measured headroom; the budget probes above it only when [probingAllowed]. */
    var ceiling: Int = automaticCeiling
        private set

    /** Whether the budget may try tiles above [ceiling] on measured headroom (Automatic only). */
    var probingAllowed: Boolean = true
        private set

    /** Hard upper bound, probes included. */
    val maxLive: Int get() = if (probingAllowed) ceiling + EXTRA_LIVE_TILES else ceiling

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

    private val automaticNote =
        "auto: lowRam=${capabilities.isLowRamDevice}, ram=${capabilities.totalMemoryMb} MB, " +
            "cores=${capabilities.cpuCores}"

    init {
        onDecision("live budget: ceiling=$ceiling ($automaticNote)")
    }

    /**
     * Replaces the ceiling: [ceiling] tiles may be live, and above it the budget probes only when
     * [probing]. The user asked for this, so it takes effect at once: the limit becomes the new
     * ceiling (clamped down when it was above, raised when it was below) and the lag, calm and
     * change timers start over, exactly as a budget created with this ceiling would. A call that
     * changes nothing is ignored, so re-applying settings on every grid change costs no decoders.
     */
    fun setCeiling(
        ceiling: Int,
        probing: Boolean,
    ) {
        require(ceiling >= MIN_LIVE) { "ceiling must be at least $MIN_LIVE, was $ceiling" }
        if (ceiling == this.ceiling && probing == probingAllowed) return
        this.ceiling = ceiling
        probingAllowed = probing
        liveLimit = ceiling
        lagSince = null
        calmSince = null
        lastChangeAt = null
        lastPromotionAt = null
        headroomMillis = HEADROOM_PROMOTE_MILLIS
        onDecision("live budget: ceiling=$ceiling (${if (probing) automaticNote else "user"})")
    }

    /**
     * Feeds one measurement: [maxQueuedFrames] is the deepest input queue among live decoders,
     * [idleFraction] and [spareMemoryBytes] the [HeadroomProbe]'s readings (defaults: unknown,
     * which keeps the budget at or below the ceiling, and sheds any tile already above it), and
     * [tileCount] how many tiles there are: the limit never probes past them, so a small grid
     * cannot leave a limit behind that the next, bigger grid would start all at once. Returns the
     * limit in force after this sample.
     */
    fun sample(
        nowMillis: Long,
        maxQueuedFrames: Int,
        idleFraction: Float = 0f,
        spareMemoryBytes: Long = 0L,
        tileCount: Int = Int.MAX_VALUE,
    ): Int {
        val settled = lastChangeAt?.let { nowMillis - it >= MIN_CHANGE_INTERVAL_MILLIS } ?: true
        val probing = liveLimit > ceiling
        val starved = probing && (idleFraction < IDLE_DEMOTE_FRACTION || spareMemoryBytes < LOW_MEMORY_BYTES)
        when {
            maxQueuedFrames > LAG_FRAMES -> onLag(nowMillis, "queue=$maxQueuedFrames frames", settled)
            starved -> onLag(nowMillis, "idle=${percent(idleFraction)} %, spare=${spareMemoryBytes / MB} MB", settled)
            else -> onCalm(nowMillis, settled, idleFraction, spareMemoryBytes, tileCount)
        }
        return liveLimit
    }

    private fun onLag(
        nowMillis: Long,
        reason: String,
        settled: Boolean,
    ) {
        calmSince = null
        val since = lagSince ?: nowMillis.also { lagSince = it }
        if (nowMillis - since >= LAG_DEMOTE_MILLIS &&
            liveLimit > MIN_LIVE &&
            settled
        ) {
            demote(nowMillis, reason)
        }
    }

    private fun onCalm(
        nowMillis: Long,
        settled: Boolean,
        idleFraction: Float,
        spareMemoryBytes: Long,
        tileCount: Int,
    ) {
        lagSince = null
        val since = calmSince ?: nowMillis.also { calmSince = it }
        lastPromotionAt?.let { if (nowMillis - it >= RELAPSE_MILLIS) headroomMillis = HEADROOM_PROMOTE_MILLIS }
        if (nowMillis - since < headroomMillis || !settled) return
        val room =
            when {
                liveLimit < ceiling -> true
                liveLimit >= maxLive || liveLimit >= tileCount -> false
                else -> idleFraction >= IDLE_PROMOTE_FRACTION && spareMemoryBytes >= EXTRA_TILE_MEMORY_BYTES
            }
        if (room) promote(nowMillis, idleFraction, spareMemoryBytes)
    }

    private fun demote(
        nowMillis: Long,
        reason: String,
    ) {
        liveLimit--
        lagSince = nowMillis
        lastChangeAt = nowMillis
        val relapse = lastPromotionAt?.let { nowMillis - it < RELAPSE_MILLIS } == true
        if (relapse) headroomMillis = minOf(headroomMillis * 2, MAX_HEADROOM_MILLIS)
        lastPromotionAt = null
        val note = if (relapse) ", next promotion after ${headroomMillis / MILLIS_PER_SECOND} s calm" else ""
        onDecision("live budget: demote, $reason, limit=$liveLimit$note")
    }

    private fun promote(
        nowMillis: Long,
        idleFraction: Float,
        spareMemoryBytes: Long,
    ) {
        liveLimit++
        calmSince = nowMillis
        lastChangeAt = nowMillis
        lastPromotionAt = nowMillis
        val readings = "idle=${percent(idleFraction)} %, spare=${spareMemoryBytes / MB} MB"
        val probe = if (liveLimit > ceiling) " above the ceiling ($readings)" else ""
        onDecision("live budget: promote, limit=$liveLimit$probe")
    }

    private fun percent(fraction: Float): Int = Math.round(fraction * PERCENT)

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

        /** Tiles the budget may probe above the static ceiling. */
        const val EXTRA_LIVE_TILES: Int = 4

        /**
         * Idle CPU needed before a tile above the ceiling is tried. The probe's reading tracks the
         * device's total idle share (on the MiTV it read 15-18 % while `top` showed 13-19 %). One
         * more software tile took 12-17 points off that reading on the MiTV (31 % → 14 %, 25 % →
         * 12 %), more than its 22 % of one core suggests, because the probe thread is the first to
         * lose its turn. So the gate is a tile's measured cost plus the demote margin: a promotion at
         * 25 % was shed within a minute, twice, on the 12 fps recorder; at 35 % it holds.
         */
        const val IDLE_PROMOTE_FRACTION: Float = 0.35f

        /** Below this a tile above the ceiling is shed, like lag: the UI thread is next in line. */
        const val IDLE_DEMOTE_FRACTION: Float = 0.10f

        private const val MILLIS_PER_SECOND = 1_000L
        private const val PERCENT = 100f

        private const val MB = 1024L * 1024L

        /** Memory a tile above the ceiling needs to spare: a software decoder (~17 MB) and its share of the app, with margin. */
        const val EXTRA_TILE_MEMORY_BYTES: Long = 96 * MB

        /** Spare memory below which a tile above the ceiling is shed. */
        const val LOW_MEMORY_BYTES: Long = 32 * MB
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
