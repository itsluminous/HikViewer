package com.itsluminous.hikviewer.data.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.itsluminous.hikviewer.ui.common.FormFactor
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
 * The choice is made by asking the package manager what handles [Intent.ACTION_CREATE_DOCUMENT]
 * ([choose]), not by [com.itsluminous.hikviewer.ui.common.FormFactor]: a TV with a file picker
 * should get the picker, and a phone whose picker was removed should still be able to back up.
 * The form factor is a hint: Android TV ships `com.android.tv.frameworkpackagestubs`, whose
 * `DocumentsStub` accepts the intent only to show a "not supported" toast and cancel, so on a
 * television a handler from a stubs package does not count. A phone has no such stubs, so there
 * every handler counts.
 */
sealed interface BackupStorage {
    /** Picks the storage for this device, see the interface note. */
    companion object {
        /** The AOSP TV package that stubs out documents intents; see the interface note. */
        const val TV_STUBS_PACKAGE: String = "com.android.tv.frameworkpackagestubs"

        /** The intent whose handler decides between the two storages. */
        fun documentsUiIntent(): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(BackupCodec.MIME_TYPE)

        /** True when a real documents UI would open for [documentsUiIntent]; a TV's stub does not count. */
        fun hasDocumentsUi(context: Context): Boolean {
            val handlers =
                context.packageManager.queryIntentActivities(documentsUiIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            val television = FormFactor.isTelevision(context)
            return handlers.any { !television || !isStub(it) }
        }

        /** [SafBackupStorage] when a documents UI resolves, else [TvBackupStorage] for this API level. */
        fun choose(context: Context): BackupStorage = if (hasDocumentsUi(context)) {
            SafBackupStorage(context.contentResolver)
        } else {
            TvBackupStorage.forDevice(context)
        }

        private fun isStub(info: ResolveInfo): Boolean {
            val packageName = info.activityInfo?.packageName ?: return false
            return packageName == TV_STUBS_PACKAGE || packageName.endsWith(".frameworkpackagestubs")
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
    /**
     * Writes [text] as the whole content of [uri] (an existing file is truncated, not appended to).
     * Throws [IOException] when the provider cannot open it or refuses the Uri.
     */
    fun write(
        uri: Uri,
        text: String,
    ) = BackupText.io {
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri for writing")
        stream.use { it.write(BackupText.bytes(text)) }
    }

    /**
     * Reads the whole content of [uri] as UTF-8, at most [BackupText.MAX_BYTES]: the picker lets
     * the user choose any file. Throws [IOException] past that, or when the provider refuses.
     */
    fun read(uri: Uri): String = BackupText.io {
        val stream = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")
        stream.use(BackupText::read)
    }

    /**
     * The file name the documents provider shows for [uri], or the last path segment when it has
     * none or will not say. Never throws: it only labels a dialog.
     */
    fun displayName(uri: Uri): String {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
        } catch (_: SecurityException) {
            // A provider that refuses the query still gets the Uri's own name below.
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
 * Backups on a device without a documents UI, in places the app may use with no permission.
 *
 * [written] is where Backup puts its file: the Downloads collection on API 29 and later, so the
 * file lands in `/sdcard/Download/` where `adb pull` and a file manager find it; the app's own
 * external directory before. [dropped] is where the user can put a file for Restore: the app's
 * external directory on every API level, because from API 29 an app without a storage permission
 * cannot read a file in `/sdcard/Download/` that it did not write itself. MediaStore hides the
 * row (an `adb push` leaves it owned by `com.android.shell`, and a display-name filter does not
 * help) and the file system answers EACCES, as verified on the API 34 TV emulator. Restore lists
 * both places, every file named like a backup, newest first.
 */
class TvBackupStorage(
    private val written: BackupFiles,
    private val dropped: BackupFiles = written,
) : BackupStorage {
    /** The directory a backup is written to, for `adb pull`. */
    val location: String get() = written.location

    /** The directory a file from outside goes into, for `adb push`. */
    val dropLocation: String get() = dropped.location

    /** Writes [text] as a new file called [name]; returns where it went. */
    fun write(
        name: String,
        text: String,
    ): BackupEntry = written.write(name, text)

    /** The backups found in both places, newest first. */
    fun list(): List<BackupEntry> = sources
        .flatMap { it.list() }
        .filter { BackupCodec.isBackupFileName(it.name) }
        .sortedWith(compareByDescending<BackupEntry> { it.modified ?: Instant.MIN }.thenByDescending { it.name })

    /** Reads [entry] as UTF-8 from the place that listed it; at most [BackupText.MAX_BYTES]. */
    fun read(entry: BackupEntry): String {
        val source =
            sources.firstOrNull { it.owns(entry) } ?: throw IOException("${entry.name} is not in a known place")
        return source.read(entry)
    }

    private val sources: List<BackupFiles> = if (dropped === written) listOf(written) else listOf(written, dropped)

    /** Picks by API level. */
    companion object {
        /** Downloads plus the app directory from API 29; the app directory alone before. */
        fun forDevice(context: Context): TvBackupStorage {
            val appDir = AppDirBackupFiles(context.getExternalFilesDir(null) ?: context.filesDir)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                TvBackupStorage(DownloadsBackupFiles(context.contentResolver), appDir)
            } else {
                TvBackupStorage(appDir)
            }
        }
    }
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

    /** True when [entry] came from this directory's [list]. */
    fun owns(entry: BackupEntry): Boolean

    /** The content of [entry] as UTF-8; an [IOException] past [BackupText.MAX_BYTES] or when it cannot be opened. */
    fun read(entry: BackupEntry): String
}
