package com.itsluminous.hikviewer.ui.settings

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.data.backup.BackupCodec
import com.itsluminous.hikviewer.data.backup.BackupEntry
import com.itsluminous.hikviewer.data.backup.BackupFormatException
import com.itsluminous.hikviewer.data.backup.BackupStorage
import com.itsluminous.hikviewer.data.backup.SafBackupStorage
import com.itsluminous.hikviewer.data.backup.TvBackupStorage
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The Backup and Restore rows of Settings.
 *
 * Both start with a dialog that says what a backup holds and that login and encryption password
 * are not in it. With a documents UI ([SafBackupStorage]) the dialog's button launches
 * `CreateDocument` with the suggested name, or `OpenDocument`, and the result `Uri` is written or
 * read. Without one ([TvBackupStorage]) Backup writes straight away and the confirmation names
 * the path for `adb pull`; Restore lists the backups found, newest first, as a single-choice
 * list of name and date, or tells the user where to `adb push` a file when there is none.
 *
 * A restore applies to the stores through [BackupCodec.restore], which changes nothing when the
 * file is refused; the error dialog says so. On success [onRestored] lets the screen rebuild its
 * rows; the grid re-applies the settings when Settings returns, as after any other change.
 *
 * Must be created before the activity is started: the two launchers register then.
 */
class BackupController(
    private val activity: AppCompatActivity,
    private val codec: BackupCodec,
    private val storage: BackupStorage,
    private val onRestored: () -> Unit,
) {
    private val create: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument(BackupCodec.MIME_TYPE)) { uri ->
            if (uri != null) writeTo(uri)
        }

    private val open: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) restoreFrom(uri)
        }

    /** The Backup row was pressed. */
    fun backup() {
        val message =
            when (storage) {
                is SafBackupStorage -> activity.getString(R.string.backup_explain)
                is TvBackupStorage -> activity.getString(R.string.backup_explain_tv, storage.location)
            }
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.settings_backup)
            .setMessage(message)
            .setPositiveButton(R.string.backup_action) { _, _ -> startBackup() }
            .setNegativeButton(R.string.code_cancel, null)
            .show()
    }

    /** The Restore row was pressed. */
    fun restore() {
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.settings_restore)
            .setMessage(R.string.restore_explain)
            .setPositiveButton(R.string.restore_action) { _, _ -> startRestore() }
            .setNegativeButton(R.string.code_cancel, null)
            .show()
    }

    private fun startBackup() {
        when (storage) {
            is SafBackupStorage -> create.launch(codec.suggestedFileName())
            is TvBackupStorage -> {
                val entry =
                    try {
                        storage.write(codec.suggestedFileName(), codec.encode())
                    } catch (e: IOException) {
                        showMessage(R.string.settings_backup, activity.getString(R.string.backup_failed, e.message))
                        return
                    }
                showMessage(R.string.backup_done_title, activity.getString(R.string.backup_done, entry.location))
            }
        }
    }

    private fun startRestore() {
        when (storage) {
            is SafBackupStorage -> open.launch(arrayOf(BackupCodec.MIME_TYPE, "*/*"))
            is TvBackupStorage -> pickFromList(storage)
        }
    }

    private fun pickFromList(tv: TvBackupStorage) {
        val entries = tv.list()
        if (entries.isEmpty()) {
            showMessage(R.string.settings_restore, activity.getString(R.string.restore_empty, tv.location))
            return
        }
        val labels = entries.map { activity.getString(R.string.restore_entry, it.name, dateOf(it)) }.toTypedArray()
        var chosen = 0
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.restore_pick_title)
            .setSingleChoiceItems(labels, chosen) { _, which -> chosen = which }
            .setPositiveButton(
                R.string.restore_action,
            ) { _, _ -> applyText(entries[chosen].name) { tv.read(entries[chosen]) } }
            .setNegativeButton(R.string.code_cancel, null)
            .show()
    }

    private fun writeTo(uri: Uri) {
        val saf = storage as? SafBackupStorage ?: return
        try {
            saf.write(uri, codec.encode())
        } catch (e: IOException) {
            showMessage(R.string.settings_backup, activity.getString(R.string.backup_failed, e.message))
            return
        }
        showMessage(R.string.backup_done_title, activity.getString(R.string.backup_done, saf.displayName(uri)))
    }

    private fun restoreFrom(uri: Uri) {
        val saf = storage as? SafBackupStorage ?: return
        applyText(saf.displayName(uri)) { saf.read(uri) }
    }

    /** Reads with [read], restores, and reports either way; the stores stay as they were when either step fails. */
    private fun applyText(
        name: String,
        read: () -> String,
    ) {
        val text =
            try {
                read()
            } catch (e: IOException) {
                showMessage(R.string.restore_failed_title, activity.getString(R.string.restore_failed, e.message))
                return
            }
        try {
            codec.restore(text)
        } catch (e: BackupFormatException) {
            showMessage(R.string.restore_failed_title, activity.getString(R.string.restore_failed, e.message))
            return
        }
        onRestored()
        showMessage(R.string.restore_done_title, activity.getString(R.string.restore_done, name))
    }

    private fun showMessage(
        titleRes: Int,
        message: String,
    ) {
        AlertDialog
            .Builder(activity)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dateOf(entry: BackupEntry): String =
        entry.modified?.atZone(ZoneId.systemDefault())?.format(DATE_FORMAT) ?: ""

    private companion object {
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(
                FormatStyle.MEDIUM,
                FormatStyle.SHORT,
            )
    }
}
