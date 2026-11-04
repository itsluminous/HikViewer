package com.itsluminous.hikviewer.ui.grid

import com.itsluminous.hikviewer.core.devices.Camera

/** Which cameras the grid shows and in what order. Pure so the rules are unit-tested. */
object VisibleCameras {
    /**
     * [chosenKeys] from settings, in display order, mapped to [all]; keys of cameras that vanished
     * are dropped. Without a choice: every camera with a signal, in channel order (the brief's
     * default for a 40-channel cloud listing of a 16-channel recorder).
     */
    fun resolve(
        all: List<Camera>,
        chosenKeys: List<String>?,
    ): List<Camera> {
        if (chosenKeys == null) return all.filter { it.hasSignal }
        val byKey = all.associateBy { it.key }
        return chosenKeys.mapNotNull(byKey::get)
    }
}
