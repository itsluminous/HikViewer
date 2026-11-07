package com.itsluminous.hikviewer.ui.grid

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.itsluminous.hikviewer.R

/**
 * The top bar's Layout: a single-choice list of the layouts worth offering (see
 * [GridLayoutMode.available]), the one in force checked and selected so a remote's D-pad starts
 * on it and the mark says where the grid stands. A `PopupMenu` with checkable items draws the same
 * radio mark but always opens with its first row selected and has no way to move that, so on a TV
 * the user would land on "Auto" however far down the current layout is. The same `AlertDialog`
 * list the tile menu's Change uses ([CameraPickerDialog]) gives both. Picking an entry closes the
 * dialog and hands the layout to [onPick]; Back closes it with nothing chosen.
 *
 * [current] is the stored layout. When it is not in the list (a landscape shape stored on a phone
 * held upright, drawn as AUTO there and not offered), nothing is checked.
 */
object LayoutPickerDialog {
    /** Shows the list; [onDismiss] runs however the dialog closes (focus goes back to the bar). */
    fun show(
        activity: Activity,
        modes: List<GridLayoutMode>,
        current: GridLayoutMode,
        onPick: (GridLayoutMode) -> Unit,
        onDismiss: () -> Unit,
    ): AlertDialog {
        val names = modes.map { activity.getString(it.labelRes) }.toTypedArray<CharSequence>()
        val checked = modes.indexOf(current)
        val dialog =
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.grid_layout_button)
                .setSingleChoiceItems(names, checked) { d, which ->
                    d.dismiss()
                    onPick(modes[which])
                }.create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.show()
        return dialog
    }
}
