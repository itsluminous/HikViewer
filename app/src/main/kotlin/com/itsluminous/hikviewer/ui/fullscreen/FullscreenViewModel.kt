package com.itsluminous.hikviewer.ui.fullscreen

import androidx.lifecycle.ViewModel
import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.data.AccountRepository
import com.itsluminous.hikviewer.data.SettingsStore
import com.itsluminous.hikviewer.player.PlaybackCoordinator
import com.itsluminous.hikviewer.ui.grid.VisibleCameras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One camera, full size, on a hardware decoder. LEFT/RIGHT step through the grid's visible
 * cameras (same order as the tiles) so the index handed back to the grid is the tile to refocus.
 */
class FullscreenViewModel(
    account: AccountRepository,
    settings: SettingsStore,
    startIndex: Int,
    coordinatorFactory: (FullscreenViewModel) -> PlaybackCoordinator,
) : ViewModel() {
    /** Cameras in grid order. */
    val cameras: List<Camera> =
        VisibleCameras.resolve(
            account.inventory.value
                ?.cameras
                .orEmpty(),
            settings.visibleCameraKeys,
        )

    private val indexFlow = MutableStateFlow(startIndex.coerceIn(0, (cameras.size - 1).coerceAtLeast(0)))

    /** Index of the camera on screen. */
    val index: StateFlow<Int> = indexFlow.asStateFlow()

    /** Single-tile playback; the focused tile is index 0 so it gets the hardware decoder. */
    val playback: PlaybackCoordinator = coordinatorFactory(this)

    /** The camera on screen, or null when the grid is empty. */
    val camera: Camera? get() = cameras.getOrNull(indexFlow.value)

    /** Steps [delta] cameras, wrapping around. */
    fun step(delta: Int) {
        if (cameras.isEmpty()) return
        indexFlow.value = (indexFlow.value + delta).mod(cameras.size)
        show()
    }

    /** Points playback at the current camera. */
    fun show() {
        camera?.let { playback.setCameras(listOf(it)) }
        playback.focusedIndex = 0
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
