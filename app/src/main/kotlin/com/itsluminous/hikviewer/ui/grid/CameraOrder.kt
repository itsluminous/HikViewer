package com.itsluminous.hikviewer.ui.grid

import com.itsluminous.hikviewer.core.devices.Camera
import com.itsluminous.hikviewer.data.GridArrangementStore
import com.itsluminous.hikviewer.data.SettingsStore

/**
 * The one definition of "the cameras in grid order".
 *
 * The grid hands a *position* to the fullscreen and phone camera screens and takes one back to
 * refocus the tile, so all three must agree on the list: Settings' visible cameras
 * ([VisibleCameras]) in the arrangement the user moved them into for the last layout
 * ([GridArrangement]). Resolving the Settings list alone (the round-2 bug) opened the wrong camera
 * as soon as a tile had been moved or a hero chosen.
 */
object CameraOrder {
    /** [all] cameras filtered and ordered as the grid shows them. */
    fun arranged(
        all: List<Camera>,
        settings: SettingsStore,
        arrangement: GridArrangementStore,
    ): List<Camera> {
        val visible = VisibleCameras.resolve(all, settings.visibleCameraKeys)
        return GridArrangement.apply(visible, arrangement.order(arrangement.lastLayout))
    }
}
