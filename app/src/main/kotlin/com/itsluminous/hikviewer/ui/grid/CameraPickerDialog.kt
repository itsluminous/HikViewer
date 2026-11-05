package com.itsluminous.hikviewer.ui.grid

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.devices.Camera

/**
 * The tile menu's Change: a single-choice list of the cameras on the grid, in grid order (the
 * order a Move leaves them in), the tile's current camera checked and selected so the D-pad
 * starts from it. A plain `AlertDialog` list is already
 * a focusable list that a remote, a mouse and a finger all drive, which is why there is no custom
 * view here. Picking an entry closes the dialog and hands the key to [onPick]; Back closes it
 * with nothing chosen.
 */
object CameraPickerDialog {
    /** Shows the list; [onDismiss] runs however the dialog closes (focus goes back to the tile). */
    fun show(
        activity: Activity,
        cameras: List<Camera>,
        currentKey: String,
        onPick: (String) -> Unit,
        onDismiss: () -> Unit,
    ): AlertDialog {
        val names = cameras.map { it.name }.toTypedArray<CharSequence>()
        val checked = cameras.indexOfFirst { it.key == currentKey }
        val dialog =
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.tile_change_title)
                .setSingleChoiceItems(names, checked) { d, which ->
                    d.dismiss()
                    onPick(cameras[which].key)
                }.create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        return dialog
    }
}
