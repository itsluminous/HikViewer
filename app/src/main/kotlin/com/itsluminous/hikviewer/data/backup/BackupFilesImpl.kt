package com.itsluminous.hikviewer.data.backup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * The Downloads collection (`MediaStore.Downloads`) on API 29 and later: an app may insert there
 * and read back what it inserted with no permission, and the file lands in `/sdcard/Download/`
 * where `adb pull` and any file manager find it. The list asks for every file named like a
 * backup, not only the app's own rows, but without a storage permission the provider only
 * returns the app's own (see [TvBackupStorage]); files from outside go to [AppDirBackupFiles].
 */
@RequiresApi(Build.VERSION_CODES.Q)
class DownloadsBackupFiles(
    private val resolver: ContentResolver,
) : BackupFiles {
    override val location: String get() = "$downloadsDir/"

    override fun write(
        name: String,
        text: String,
    ): BackupEntry {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, BackupCodec.MIME_TYPE)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(collection, values) ?: throw IOException("MediaStore refused to create $name")
        try {
            BackupText.io {
                val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri for writing")
                stream.use { it.write(BackupText.bytes(text)) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
        } catch (e: IOException) {
            // Without this the half-written row stays pending and invisible, holding the name.
            resolver.delete(uri, null, null)
            throw e
        }
        // MediaStore renames a file whose name is taken ("... (1).json"); tell the user that name.
        val stored = displayNameOf(uri) ?: name
        return BackupEntry(stored, Instant.now(), uri, "$downloadsDir/$stored")
    }

    private fun displayNameOf(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    override fun list(): List<BackupEntry> {
        val projection =
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED)
        val cursor =
            resolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("${BackupCodec.FILE_PREFIX}%${BackupCodec.FILE_SUFFIX}"),
                null,
            ) ?: return emptyList()
        return cursor.use {
            generateSequence { if (it.moveToNext()) it else null }
                .map { row ->
                    val name = row.getString(1)
                    BackupEntry(
                        name = name,
                        modified = if (row.isNull(2)) null else Instant.ofEpochSecond(row.getLong(2)),
                        uri = ContentUris.withAppendedId(collection, row.getLong(0)),
                        location = "$downloadsDir/$name",
                    )
                }.toList()
        }
    }

    override fun owns(entry: BackupEntry): Boolean = entry.uri.scheme == ContentResolver.SCHEME_CONTENT

    override fun read(entry: BackupEntry): String = BackupText.io {
        val stream = resolver.openInputStream(entry.uri) ?: throw IOException("Cannot open ${entry.uri} for reading")
        stream.use(BackupText::read)
    }

    private val collection: Uri get() = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    // Deprecated since 29 as a place to write, but it is still the path the user sees in a file
    // manager or adb, which is all it is used for here.
    @Suppress("DEPRECATION")
    private val downloadsDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
}

/**
 * The app's own external files directory, `/sdcard/Android/data/<package>/files/`: readable and
 * writable with no permission on every API level, and `adb push` and `adb pull` reach it (checked
 * on the API 34 TV emulator). The only place Backup writes on API 26 to 28, where the Downloads
 * collection does not exist; the place Restore reads files from outside on every level.
 */
class AppDirBackupFiles(
    private val dir: File,
) : BackupFiles {
    override val location: String get() = "${dir.absolutePath}/"

    override fun write(
        name: String,
        text: String,
    ): BackupEntry {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.absolutePath}")
        val file = File(dir, name)
        file.writeBytes(BackupText.bytes(text))
        return entry(file)
    }

    override fun list(): List<BackupEntry> = dir
        .listFiles()
        ?.filter { it.isFile }
        ?.map(::entry)
        .orEmpty()

    override fun owns(entry: BackupEntry): Boolean =
        entry.uri.scheme == ContentResolver.SCHEME_FILE && entry.uri.path?.startsWith(dir.absolutePath) == true

    override fun read(entry: BackupEntry): String {
        val file = File(entry.uri.path ?: entry.location)
        if (file.length() > BackupText.MAX_BYTES) throw BackupText.tooLarge()
        return file.inputStream().use(BackupText::read)
    }

    private fun entry(file: File) =
        BackupEntry(file.name, Instant.ofEpochMilli(file.lastModified()), Uri.fromFile(file), file.absolutePath)
}
