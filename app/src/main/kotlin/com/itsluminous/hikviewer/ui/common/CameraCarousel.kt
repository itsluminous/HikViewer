package com.itsluminous.hikviewer.ui.common

import com.itsluminous.hikviewer.core.devices.Camera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A position in a fixed list of cameras that LEFT/RIGHT (or a swipe) move through with
 * wrap-around. The fullscreen and the phone camera screens both step through the grid's cameras
 * this way; the index is what they hand back to the grid so the same tile regains focus.
 */
class CameraCarousel(
    /** The cameras, in grid order (see [com.itsluminous.hikviewer.ui.grid.CameraOrder]). */
    val cameras: List<Camera>,
    startIndex: Int,
) {
    private val indexFlow = MutableStateFlow(startIndex.coerceIn(0, (cameras.size - 1).coerceAtLeast(0)))

    /** Index of the camera on screen. */
    val index: StateFlow<Int> = indexFlow.asStateFlow()

    /** The camera on screen, or null when there is none. */
    val camera: Camera? get() = cameras.getOrNull(indexFlow.value)

    /** Moves [delta] cameras on, wrapping around. Returns false when there is nothing to step through. */
    fun step(delta: Int): Boolean {
        if (cameras.isEmpty()) return false
        indexFlow.value = (indexFlow.value + delta).mod(cameras.size)
        return true
    }
}
