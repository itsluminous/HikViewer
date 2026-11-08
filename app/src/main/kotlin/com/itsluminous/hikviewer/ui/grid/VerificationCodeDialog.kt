package com.itsluminous.hikviewer.ui.grid

import android.app.Activity
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.itsluminous.hikviewer.R

/**
 * Asks for the video verification code once per install. The dialog stays open on a wrong code and
 * shows an inline error, because closing and reopening it on a TV costs a dozen key presses.
 */
object VerificationCodeDialog {
    private var open: AlertDialog? = null

    /** Shows the dialog unless it is already open; [submit] returns true when the code matched. */
    fun show(
        activity: Activity,
        rejected: Boolean,
        submit: (String) -> Boolean,
    ) {
        open?.takeIf { it.isShowing }?.let { return }
        val padding = activity.resources.getDimensionPixelSize(R.dimen.tile_gap) * 3
        val input =
            EditText(activity).apply {
                id = R.id.code_input
                hint = activity.getString(R.string.code_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
                // The keyboard's action key is the natural way to submit on a phone and on a TV
                // keyboard alike; without an action set it does nothing, not even hide.
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
        val error =
            TextView(activity).apply {
                id = R.id.code_error
                setText(R.string.code_wrong)
                setTextColor(activity.getColor(R.color.error_text))
                visibility = if (rejected) View.VISIBLE else View.GONE
            }
        val content =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(input)
                addView(error)
            }
        val dialog =
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.code_title)
                .setMessage(R.string.code_message)
                .setView(content)
                .setPositiveButton(R.string.code_ok, null)
                .setNegativeButton(R.string.code_cancel) { _, _ -> activity.finish() }
                .setCancelable(false)
                .create()
        dialog.setOnShowListener { wireSubmit(dialog, input, error, submit) }
        dialog.setOnDismissListener { if (open === dialog) open = null }
        open = dialog
        dialog.show()
    }

    /** OK checks the code; the keyboard's Done key and a hardware Enter are a press of OK. */
    private fun wireSubmit(
        dialog: AlertDialog,
        input: EditText,
        error: TextView,
        submit: (String) -> Boolean,
    ) {
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.setOnClickListener {
            if (submit(input.text.toString())) {
                dialog.dismiss()
            } else {
                error.visibility = View.VISIBLE
                input.selectAll()
            }
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                ok.performClick()
                true
            } else {
                false
            }
        }
        input.requestFocus()
    }

    /**
     * Closes the dialog if it is open. The activity calls this from `onDestroy`: a dialog is a
     * window of the activity that showed it, and a phone rotation recreates the grid activity, so
     * an open dialog would be reported as leaked and the new activity shows its own.
     */
    fun dismiss() {
        open?.dismiss()
        open = null
    }
}
