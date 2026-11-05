package com.itsluminous.hikviewer.player

import android.util.Log
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.stream.StreamQuality
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Future

/**
 * Runs every tile of the grid: one [TilePlayer] per visible camera, the [LiveBudget] that decides
 * how many are live, and the [DecoderPolicy] that hands the hardware decoder to the hero tile.
 * Focus is not an input: moving it must never touch a decoder (see [DecoderPolicy]). All streams
 * are opened in parallel (the first frame takes 1.4–3.2 s each; opening them one by one would
 * make a 12-tile grid take half a minute). A ticker samples decoder lag and runs the stall
 * watchdogs. `stop()` releases everything; the owner calls it from `onStop`.
 */
class PlaybackCoordinator(
    private val opener: StreamOpener,
    capabilities: DeviceCapabilities,
    private val scope: CoroutineScope,
    private val quality: StreamQuality = StreamQuality.SUB,
    private val pool: KeyframeDecoderPool = KeyframeDecoderPool(),
    private val pickCodec: (String, Boolean) -> String? = CodecNames::pick,
    private val decoderFactory: DecoderFactory = DecoderFactory.DEFAULT,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val dispatcher: CoroutineDispatcher = StreamThreads.dispatcher,
    /** How players tear decoders down; tests pass a synchronous one. */
    private val releaser: (TileDecoder) -> Future<*> = DecoderReleaser::release,
    /** CPU and memory readings for probing above the static ceiling; [HeadroomProbe.NONE] never probes. */
    private val headroom: HeadroomProbe = HeadroomProbe.NONE,
) {
    private val budget = LiveBudget(capabilities) { Log.i(TAG, it) }
    private val playersFlow = MutableStateFlow<List<TilePlayer>>(emptyList())
    private var ticker: Job? = null
    private var running = false
    private var lastStallAt: Long? = null
    private var lastReportAt = 0L

    /** One player per visible camera, in grid order. */
    val players: StateFlow<List<TilePlayer>> = playersFlow.asStateFlow()

    /** Tile shown 2x in the hero layout (or the only tile of a single-camera screen); gets the hardware decoder. */
    var heroIndex: Int? = null
        set(value) {
            if (field == value) return
            field = value
            applyPolicy()
        }

    /**
     * While true only the hero decodes live; every other tile shows keyframe stills. The grid sets
     * it for the duration of a tile move: on the target TV eight software decoders run at the
     * same scheduler priority as the UI thread, so the remote answers a second late while they
     * all decode. Cleared on drop, cancel and stop; the normal budget comes straight back.
     */
    var hardwareOnly: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            Log.i(TAG, if (value) "live decoding limited to the hardware tiles" else "live budget restored")
            applyPolicy()
        }

    /** Live-tile limit currently in force (for diagnostics). */
    val liveLimit: Int get() = budget.liveLimit

    /** Replaces the camera set; players of cameras no longer shown are stopped. */
    fun setCameras(cameras: List<Camera>) {
        val existing = playersFlow.value.associateBy { it.camera.key }
        val next =
            cameras.map { camera -> existing[camera.key] ?: newPlayer(camera) }
        existing.values.filter { it !in next }.forEach { it.stop() }
        playersFlow.value = next
        if (running) {
            next.forEach { it.start(scope) }
            probeIfWorthIt()
        }
        applyPolicy()
    }

    /** The probe thread only runs when there is a tile beyond the static ceiling to win. */
    private fun probeIfWorthIt() {
        if (playersFlow.value.size > budget.ceiling) headroom.start() else headroom.stop()
    }

    private fun newPlayer(camera: Camera) =
        TilePlayer(camera, opener, pool, quality, pickCodec, decoderFactory, dispatcher, releaser = releaser)

    /** Opens every stream and starts the watchdog ticker. */
    fun start() {
        if (running) return
        running = true
        playersFlow.value.forEach { it.start(scope) }
        probeIfWorthIt()
        applyPolicy()
        // On the players' dispatcher, not the owner's (main-thread) scope: a tick walks every
        // player's lock and may detach a stalled decoder, none of which belongs on the UI thread.
        ticker =
            scope.launch(dispatcher) {
                while (isActive) {
                    delay(tickMillis)
                    tick()
                }
            }
    }

    /** Stops every stream, releases every decoder, including the keyframe pool's. Idempotent. */
    fun stop() {
        if (!running) return
        running = false
        ticker?.cancel()
        ticker = null
        headroom.stop()
        playersFlow.value.forEach { it.stop() }
        pool.closeCodecs()
        hardwareOnly = false
    }

    /** Reopens one failed tile's stream (the Retry overlay); a no-op while playback is stopped. */
    fun retry(player: TilePlayer) {
        if (!running || player !in playersFlow.value) return
        player.retry(scope)
    }

    /** Releases the shared pool too; call when the owner dies. */
    fun release() {
        stop()
        pool.release()
    }

    private fun tick() {
        val now = clock()
        val players = playersFlow.value
        val before = budget.liveLimit
        // A decoder that stalled with a deep input queue is the device not keeping up, as much as
        // a deep queue alone is; it counts as lag for the whole demote window so the budget sheds
        // a tile instead of letting the watchdog recreate decoders forever. A stall with an empty
        // queue is the codec wedging on its own (the MiTV's software decoder does) and is only
        // recreated, never demoted for: that would turn a one-second hiccup into a slideshow tile.
        val stalls = players.mapNotNull { it.tick(now) }
        if (stalls.any { it > LiveBudget.LAG_FRAMES }) lastStallAt = now
        val recentStall = lastStallAt?.let { now - it <= LiveBudget.LAG_DEMOTE_MILLIS } == true
        val queued = players.filter { it.mode.isLive }.maxOfOrNull { it.queuedFrames } ?: 0
        val lag = if (recentStall) maxOf(queued, LiveBudget.LAG_FRAMES + 1) else queued
        val idle = headroom.cpuIdleFraction
        val spare = headroom.spareMemoryBytes
        if (budget.sample(now, lag, idle, spare) != before) applyPolicy()
        if (now - lastReportAt >= REPORT_MILLIS) {
            lastReportAt = now
            val spareText = if (spare == Long.MAX_VALUE) "?" else "${spare / MB}"
            val idlePercent = Math.round(idle * PERCENT)
            Log.i(
                DIAG_TAG,
                "live limit=${budget.liveLimit}/${budget.ceiling}(+${budget.maxLive - budget.ceiling}) " +
                    "queued=$queued idle=$idlePercent % spare=$spareText MB pool codecs=${pool.codecsCreated}",
            )
            players.forEach { Log.i(DIAG_TAG, it.diagnostics(now)) }
        }
    }

    private fun applyPolicy() {
        val players = playersFlow.value
        val modes = DecoderPolicy.assign(players.size, heroIndex, budget.liveLimit, hardwareOnly = hardwareOnly)
        // Release first, create second: the hardware decoder a tile gives up must be free before
        // the new hero asks for one (the TV has only two).
        players.forEachIndexed { i, p -> if (p.mode.isLive && !modes[i].isLive) p.mode = modes[i] }
        players.forEachIndexed { i, p ->
            if (p.mode == TileMode.HW_LIVE &&
                modes[i] != TileMode.HW_LIVE
            ) {
                p.mode = modes[i]
            }
        }
        players.forEachIndexed { i, p -> p.mode = modes[i] }
    }

    /** Defaults. */
    companion object {
        /** Watchdog and budget sampling period. */
        const val DEFAULT_TICK_MILLIS: Long = 500

        /** One diagnostics line per tile this often. */
        const val REPORT_MILLIS: Long = 10_000
        private const val DIAG_TAG = "HikViewer"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val PERCENT = 100f
        private const val MB = 1024L * 1024L
        private const val TAG = "LiveBudget"
    }
}
