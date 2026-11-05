package com.itsluminous.hikviewer.ui.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Keeps a phone screen's content out from under the status bar, navigation bar and display cutout:
 * Android 15+ draws every Activity edge to edge, so without this the clock would sit on top of the
 * grid's bar and the LIVE badge. Televisions have no system bars and never call this.
 */
object SystemBarInsets {
    /** Pads [root] by the system bars and cutout on every side, re-applied whenever the insets change. */
    fun pad(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }
}
