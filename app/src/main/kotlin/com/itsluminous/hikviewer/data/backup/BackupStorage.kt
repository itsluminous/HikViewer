package com.itsluminous.hikviewer.data.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.IOException
import java.time.Instant

/**
 * Where backup files live on this device. Neither kind asks for a storage permission.
 *
 * [SafBackupStorage] is used wherever a documents UI exists (every phone, and a TV that happens
 * to have one): the user picks the place and the app only reads and writes the `Uri` it is
 * handed. [TvBackupStorage] is the fallback for a device without one (Android TV ships no
 * DocumentsUI): the app writes to a place it may use without permission and lists what is there.
 *
 * The choice is made by asking the package manager whether anything handles
 * [Intent.ACTION_CREATE_DOCUMENT] ([choose]), not by [com.itsluminous.hikviewer.ui.common.FormFactor]:
 * a TV with a file picker should get the picker, and a phone whose picker was removed should
 * still be able to back up. The form factor is only a hint for the words the TV screens use.
 */
sealed interface BackupStorage {
    /** Picks the storage for this device, see the interface note. */
    companion object {
        /** The intent whose handler decides between the two storages. */
        fun documentsUiIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(BackupCodec.MIME_TYPE)

        /** True when a documents UI would open for [documentsUiIntent]. */
        fun hasDocumentsUi(packageManager: PackageManager): Boolean =
            documentsUiIntent().resolveActivity(packageManager) != null

        /** [SafBackupStorage] when a documents UI resolves, else [TvBackupStorage] for this API level. */
        fun choose(context: Context): BackupStorage = if (hasDocumentsUi(context.packageManager)) {
            SafBackupStorage(context.contentResolver)
        } else {
            TvBackupStorage(BackupFiles.forDevice(context))
        }
    }
}

/**
 * Backups through the Storage Access Framework: the documents UI creates or opens the file and
 * hands back a `Uri`; this class only moves text through the content resolver. The intents
 * themselves are launched by the screen with `ActivityResultContracts.CreateDocument` and
 * `OpenDocument`, which need an `ActivityResultCaller`.
 */
class SafBackupStorage(
    private val resolver: ContentResolver,
) : BackupStorage {
    /** Writes [text] as the whole content of [uri] (an existing file is truncated, not appended to). */
    fun write(
        uri: Uri,
        text: String,
    ) {
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri for writing")
        stream.use { it.write(text.toByteArray()) }
    }

    /** Reads the whole content of [uri] as UTF-8. */
    fun read(uri: Uri): String {
        val stream = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
        return stream.use { String(it.readBytes()) }
    }

    /** The file name the documents provider shows for [uri], or the last path segment when it has none. */
    fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: uri.toString()
    }
}

/** One backup file a [TvBackupStorage] can restore from. */
data class BackupEntry(
    /** The file name, `HikViewer-backup-YYYYMMDD-HHMM.json`. */
    val name: String,
    /** When the file was last written, when the store knows. */
    val modified: Instant?,
    /** How the storage opens it again. */
    val uri: Uri,
    /** A path the user can type into `adb pull`. */
    val location: String,
)

/**
 * Backups on a device without a documents UI, in a place the app may use with no permission:
 * the Downloads collection on API 29 and later, the app's own external directory before. Lists
 * every file there named like a backup, whoever wrote it, newest first, so a file the user
 * pushed with `adb` is offered too.
 */
class TvBackupStorage(
    private val files: BackupFiles,
) : BackupStorage {
    /** The directory the user looks in (`adb pull`) or puts a file into (`adb push`). */
    val location: String get() = files.location

    /** Writes [text] as a new file called [name]; returns where it went. */
    fun write(
        name: String,
        text: String,
    ): BackupEntry = files.write(name, text)

    /** The backups found, newest first. */
    fun list(): List<BackupEntry> = files
        .list()
        .filter { BackupCodec.isBackupFileName(it.name) }
        .sortedWith(compareByDescending<BackupEntry> { it.modified ?: Instant.MIN }.thenByDescending { it.name })

    /** Reads [entry] as UTF-8. */
    fun read(entry: BackupEntry): String = files.read(entry)
}

/**
 * The files behind [TvBackupStorage]. Two implementations exist ([DownloadsBackupFiles],
 * [AppDirBackupFiles]); tests use a fake, since Robolectric has no MediaStore.
 */
interface BackupFiles {
    /** The directory as the user knows it, e.g. `/sdcard/Download/`. */
    val location: String

    /** Creates [name] with [text] as content. */
    fun write(
        name: String,
        text: String,
    ): BackupEntry

    /** Every file in the directory this storage can see, in any order and whatever its name. */
    fun list(): List<BackupEntry>

    /** The content of [entry] as UTF-8. */
    fun read(entry: BackupEntry): String

    /** Picks by API level. */
    companion object {
        /** The Downloads collection from API 29, the app's external directory before. */
        fun forDevice(context: Context): BackupFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DownloadsBackupFiles(context.contentResolver)
        } else {
            AppDirBackupFiles(context.getExternalFilesDir(null) ?: context.filesDir)
        }
    }
}
