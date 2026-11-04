package com.itsluminous.hikviewer.player

import android.util.Log
import android.view.Surface
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.core.stream.CodecConfig
import com.itsluminous.hikviewer.core.stream.StreamQuality
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs every tile of the grid: one [TilePlayer] per visible camera, the [LiveBudget] that decides
 * how many are live, and the [DecoderPolicy] that hands out hardware decoders to the focused and
 * hero tiles. All streams are opened in parallel (the first frame takes 1.4–3.2 s each; opening
 * them one by one would make a 12-tile grid take half a minute). A ticker samples decoder lag and
 * runs the stall watchdogs. `stop()` releases everything; the owner calls it from `onStop`.
 */
class PlaybackCoordinator(
    private val opener: StreamOpener,
    capabilities: DeviceCapabilities,
    private val scope: CoroutineScope,
    private val quality: StreamQuality = StreamQuality.SUB,
    private val pool: KeyframeDecoderPool = KeyframeDecoderPool(),
    private val pickCodec: (String, Boolean) -> String? = CodecNames::pick,
    private val decoderFactory: (String, CodecConfig, Surface, TileDecoder.Listener) -> TileDecoder = ::TileDecoder,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val budget = LiveBudget(capabilities) { Log.i(TAG, it) }
    private val playersFlow = MutableStateFlow<List<TilePlayer>>(emptyList())
    private var ticker: Job? = null
    private var running = false

    /** One player per visible camera, in grid order. */
    val players: StateFlow<List<TilePlayer>> = playersFlow.asStateFlow()

    /** Tile the user focuses; gets a hardware decoder. */
    var focusedIndex: Int? = null
        set(value) {
            if (field == value) return
            field = value
            applyPolicy()
        }

    /** Tile shown 2x in the hero layout; gets a hardware decoder. */
    var heroIndex: Int? = null
        set(value) {
            if (field == value) return
            field = value
            applyPolicy()
        }

    /** Live-tile limit currently in force (for diagnostics). */
    val liveLimit: Int get() = budget.liveLimit

    /** Replaces the camera set; players of cameras no longer shown are stopped. */
    fun setCameras(cameras: List<Camera>) {
        val existing = playersFlow.value.associateBy { it.camera.key }
        val next =
            cameras.map { camera ->
                existing[camera.key] ?: TilePlayer(camera, opener, pool, quality, pickCodec, decoderFactory, dispatcher)
            }
        existing.values.filter { it !in next }.forEach { it.stop() }
        playersFlow.value = next
        if (running) next.forEach { it.start(scope) }
        applyPolicy()
    }

    /** Opens every stream and starts the watchdog ticker. */
    fun start() {
        if (running) return
        running = true
        playersFlow.value.forEach { it.start(scope) }
        applyPolicy()
        ticker =
            scope.launch {
                while (isActive) {
                    delay(tickMillis)
                    tick()
                }
            }
    }

    /** Stops every stream, releases every decoder. Idempotent. */
    fun stop() {
        if (!running) return
        running = false
        ticker?.cancel()
        ticker = null
        playersFlow.value.forEach { it.stop() }
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
        val lag = players.filter { it.mode.isLive }.maxOfOrNull { it.queuedFrames } ?: 0
        if (budget.sample(now, lag) != before) applyPolicy()
        players.forEach { it.tick(now) }
    }

    private fun applyPolicy() {
        val players = playersFlow.value
        val modes = DecoderPolicy.assign(players.size, focusedIndex, heroIndex, budget.liveLimit)
        // Release first, create second: the hardware decoders a tile gives up must be free before
        // the newly focused tile asks for one (the TV has only two).
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
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val TAG = "LiveBudget"
    }
}
