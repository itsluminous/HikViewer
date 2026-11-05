package com.itsluminous.hikviewer.ui.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.data.AccountRepository
import com.itsluminous.hikviewer.data.SettingsStore
import com.itsluminous.hikviewer.player.PlaybackCoordinator
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.ui.grid.VisibleCameras
import com.itsluminous.hikviewer.ui.playback.PlaybackViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** What the phone screen's single picture shows. */
enum class PhoneCameraMode {
    /** The live stream; the time bar's playhead is the clock. */
    LIVE,

    /** Recorded footage from the time the user scrubbed to. */
    PLAYBACK,
}

/**
 * The phone's single-camera screen: live picture on top, the day's time bar underneath. Live and
 * recorded footage share one surface, so exactly one player owns it at a time ([activePlayer]):
 * the live [PlaybackCoordinator] (one camera, hardware decoder) or the recorded engine, a
 * [PlaybackViewModel] embedded per camera on this ViewModel's scope — the same engine as the TV
 * playback screen, so seek, speed and day changes behave identically.
 *
 * Scrubbing the bar stops the live stream and opens recorded footage at that time; [goLive]
 * closes the footage and reopens the live stream. Switching camera (swipe) always comes back to
 * live: the new camera's footage is listed again so the bar shows its own recordings.
 */
class PhoneCameraViewModel(
    private val account: AccountRepository,
    settings: SettingsStore,
    startIndex: Int,
    private val factory: PlaybackFactory,
    private val today: () -> LocalDate = { LocalDate.now() },
    /** Where recording searches run; a test dispatcher in tests. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    liveFactory: (PhoneCameraViewModel) -> PlaybackCoordinator,
) : ViewModel() {
    /** Cameras in grid order, so the index handed back to the grid is the tile to refocus. */
    val cameras: List<Camera> =
        VisibleCameras.resolve(
            account.inventory.value
                ?.cameras
                .orEmpty(),
            settings.visibleCameraKeys,
        )

    private val indexFlow = MutableStateFlow(startIndex.coerceIn(0, (cameras.size - 1).coerceAtLeast(0)))
    private val modeFlow = MutableStateFlow(PhoneCameraMode.LIVE)
    private val recordedFlow = MutableStateFlow<PlaybackViewModel?>(null)
    private val activePlayerFlow = MutableStateFlow<TilePlayer?>(null)
    private var visible = false

    /** Index of the camera on screen. */
    val index: StateFlow<Int> = indexFlow.asStateFlow()

    /** Live or recorded. */
    val mode: StateFlow<PhoneCameraMode> = modeFlow.asStateFlow()

    /** The recorded-footage engine of the camera on screen (null until there is a camera). */
    val recorded: StateFlow<PlaybackViewModel?> = recordedFlow.asStateFlow()

    /** The player that owns the surface right now. */
    val activePlayer: StateFlow<TilePlayer?> = activePlayerFlow.asStateFlow()

    /** Live playback of the camera on screen. */
    val live: PlaybackCoordinator = liveFactory(this)

    /** The camera on screen, or null when the grid is empty. */
    val camera: Camera? get() = cameras.getOrNull(indexFlow.value)

    /** Watchdog period for the recorded player's [TilePlayer.tick]. */
    val watchdogMillis: Long = factory.watchdogMillis

    init {
        rebuildRecorded()
    }

    /** Swipe: [delta] cameras on, wrapping around; back to live. */
    fun step(delta: Int) {
        if (cameras.isEmpty()) return
        indexFlow.value = (indexFlow.value + delta).mod(cameras.size)
        modeFlow.value = PhoneCameraMode.LIVE
        rebuildRecorded()
        showLive()
    }

    /** A tap on the bar at [fraction] of the day: recorded footage from there. */
    fun scrubTo(fraction: Float) {
        val engine = recordedFlow.value ?: return
        if (engine.state.value.segments
                .isEmpty()
        ) {
            return
        }
        if (modeFlow.value == PhoneCameraMode.LIVE) {
            live.stop()
            modeFlow.value = PhoneCameraMode.PLAYBACK
        }
        activePlayerFlow.value = engine.player
        engine.seekToFraction(fraction)
    }

    /** The Live button: drop the footage and reopen the live stream. */
    fun goLive() {
        if (modeFlow.value == PhoneCameraMode.LIVE) return
        recordedFlow.value?.onStop()
        modeFlow.value = PhoneCameraMode.LIVE
        showLive()
    }

    /** The date chip: another day on the bar; while live only the bar changes. */
    fun changeDay(delta: Int) {
        recordedFlow.value?.changeDay(delta, today())
    }

    /** The speed button; only meaningful while playing footage. */
    fun cycleSpeed() {
        if (modeFlow.value == PhoneCameraMode.PLAYBACK) recordedFlow.value?.cycleSpeed()
    }

    /** Periodic watchdog from the Activity. */
    fun tick() {
        if (modeFlow.value == PhoneCameraMode.PLAYBACK) recordedFlow.value?.tick()
    }

    /** Streams may run. */
    fun onStart() {
        visible = true
        when (modeFlow.value) {
            PhoneCameraMode.LIVE -> {
                showLive()
                recordedFlow.value?.listDay()
            }
            PhoneCameraMode.PLAYBACK -> {
                recordedFlow.value?.onStart()
            }
        }
    }

    /** Release everything. */
    fun onStop() {
        visible = false
        live.stop()
        recordedFlow.value?.onStop()
    }

    override fun onCleared() {
        live.release()
        recordedFlow.value?.close()
    }

    private fun showLive() {
        val current = camera ?: return
        live.setCameras(listOf(current))
        live.focusedIndex = 0
        activePlayerFlow.value = live.players.value.firstOrNull()
        if (visible) live.start()
    }

    private fun rebuildRecorded() {
        recordedFlow.value?.close()
        val current = camera
        recordedFlow.value =
            current?.let {
                PlaybackViewModel(it, account, factory, today(), io = io, externalScope = viewModelScope)
            }
        if (visible) recordedFlow.value?.listDay()
    }
}
