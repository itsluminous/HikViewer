package com.itsluminous.hikviewer.ui.fullscreen

import androidx.lifecycle.ViewModel
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.data.AccountRepository
import com.itsluminous.hikviewer.data.GridArrangementStore
import com.itsluminous.hikviewer.data.SettingsStore
import com.itsluminous.hikviewer.player.PlaybackCoordinator
import com.itsluminous.hikviewer.ui.common.CameraCarousel
import com.itsluminous.hikviewer.ui.grid.CameraOrder
import kotlinx.coroutines.flow.StateFlow

/**
 * One camera, full size, on a hardware decoder. LEFT/RIGHT step through the grid's cameras in
 * grid order ([CameraOrder]) so the index handed back to the grid is the tile to refocus.
 */
class FullscreenViewModel(
    account: AccountRepository,
    settings: SettingsStore,
    arrangement: GridArrangementStore,
    startIndex: Int,
    coordinatorFactory: (FullscreenViewModel) -> PlaybackCoordinator,
) : ViewModel() {
    private val carousel =
        CameraCarousel(
            CameraOrder.arranged(
                account.inventory.value
                    ?.cameras
                    .orEmpty(),
                settings,
                arrangement,
            ),
            startIndex,
        )

    /** Cameras in grid order. */
    val cameras: List<Camera> get() = carousel.cameras

    /** Index of the camera on screen. */
    val index: StateFlow<Int> get() = carousel.index

    /** Single-tile playback; the one tile is the hero so it gets the hardware decoder. */
    val playback: PlaybackCoordinator = coordinatorFactory(this)

    /** The camera on screen, or null when the grid is empty. */
    val camera: Camera? get() = carousel.camera

    /** Steps [delta] cameras, wrapping around. */
    fun step(delta: Int) {
        if (carousel.step(delta)) show()
    }

    /** Points playback at the current camera. */
    fun show() {
        camera?.let { playback.setCameras(listOf(it)) }
        playback.heroIndex = 0
    }

    /** Streams may run. */
    fun onStart() {
        show()
        playback.start()
    }

    /** Release everything. */
    fun onStop() = playback.stop()

    override fun onCleared() = playback.release()
}
