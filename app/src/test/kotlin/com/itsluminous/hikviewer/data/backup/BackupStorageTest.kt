package com.itsluminous.hikviewer.data.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.fakes.EndlessInputStream
import com.itsluminous.hikviewer.fakes.FakeBackupFiles
import com.itsluminous.hikviewer.fakes.FakeMediaProvider
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import kotlin.io.path.createTempDirectory

@RunWith(AndroidJUnit4::class)
class BackupStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Robolectric resolves no documents UI by default, like an Android TV. */
    @Test
    @Config(qualifiers = "television")
    fun `without a documents UI the TV storage is chosen, whatever the API level says`() {
        assertThat(BackupStorage.hasDocumentsUi(context)).isFalse()
        assertThat(BackupStorage.choose(context)).isInstanceOf(TvBackupStorage::class.java)
    }

    /** A phone (or any device) with a documents UI gets the Storage Access Framework, with no permission involved. */
    @Test
    fun `with a documents UI the SAF storage is chosen`() {
        plantDocumentsUi()
        assertThat(BackupStorage.hasDocumentsUi(context)).isTrue()
        assertThat(BackupStorage.choose(context)).isInstanceOf(SafBackupStorage::class.java)
    }

    /** Android TV's frameworkpackagestubs answers CREATE_DOCUMENT with a toast; it must not pass for a picker. */
    @Test
    @Config(qualifiers = "television")
    fun `on a television the framework stubs package does not count as a documents UI`() {
        plantDocumentsUi(
            BackupStorage.TV_STUBS_PACKAGE,
            "com.android.tv.frameworkpackagestubs.Stubs${'$'}DocumentsStub",
        )
        assertThat(BackupStorage.hasDocumentsUi(context)).isFalse()
        assertThat(BackupStorage.choose(context)).isInstanceOf(TvBackupStorage::class.java)
        // A real picker next to the stub does count.
        plantDocumentsUi()
        assertThat(BackupStorage.hasDocumentsUi(context)).isTrue()
    }

    /** The form factor is only a hint: on a phone every handler counts, whatever its package is called. */
    @Test
    fun `on a phone any handler counts as a documents UI`() {
        plantDocumentsUi(
            BackupStorage.TV_STUBS_PACKAGE,
            "com.android.tv.frameworkpackagestubs.Stubs${'$'}DocumentsStub",
        )
        assertThat(BackupStorage.hasDocumentsUi(context)).isTrue()
    }

    @Test
    @Config(sdk = [28])
    fun `before API 29 the TV storage writes and reads the app's external directory`() {
        val storage = BackupStorage.choose(context) as TvBackupStorage
        val appDir = context.getExternalFilesDir(null)!!.absolutePath + "/"
        assertThat(storage.location).isEqualTo(appDir)
        assertThat(storage.dropLocation).isEqualTo(appDir)
    }

    /** Downloads is where a backup is written (adb pull); the app directory is where a pushed file is read from. */
    @Test
    @Config(sdk = [34])
    fun `from API 29 the TV storage writes to Downloads and also lists the app's external directory`() {
        val storage = BackupStorage.choose(context) as TvBackupStorage
        assertThat(storage.location).endsWith("/Download/")
        assertThat(storage.dropLocation).isEqualTo(context.getExternalFilesDir(null)!!.absolutePath + "/")
    }

    @Test
    fun `the TV restore list merges both places and reads each entry from the place that listed it`() {
        val downloads = FakeBackupFiles("/sdcard/Download/")
        val appDir = FakeBackupFiles("/sdcard/Android/data/hv/files/")
        val storage = TvBackupStorage(written = downloads, dropped = appDir)
        downloads.put("HikViewer-backup-20261108-1000.json", "mine", Instant.parse("2026-11-08T10:00:00Z"))
        appDir.put("HikViewer-backup-test.json", "pushed", Instant.parse("2026-11-08T11:00:00Z"))
        appDir.put("readme.txt", "x", Instant.parse("2026-11-09T11:00:00Z"))
        downloads.now = Instant.parse("2026-11-08T12:00:00Z")
        storage.write("HikViewer-backup-20261108-1200.json", "newest")
        assertThat(downloads.contents).containsKey("HikViewer-backup-20261108-1200.json")
        assertThat(appDir.contents).doesNotContainKey("HikViewer-backup-20261108-1200.json")
        assertThat(storage.location).isEqualTo("/sdcard/Download/")
        assertThat(storage.dropLocation).isEqualTo("/sdcard/Android/data/hv/files/")
        val listed = storage.list()
        assertThat(listed.map { it.name })
            .containsExactly(
                "HikViewer-backup-20261108-1200.json",
                "HikViewer-backup-test.json",
                "HikViewer-backup-20261108-1000.json",
            ).inOrder()
        assertThat(listed.map { storage.read(it) }).containsExactly("newest", "pushed", "mine").inOrder()
    }

    @Test
    fun `the TV restore list has only backup names, newest first, whoever wrote them`() {
        val files = FakeBackupFiles()
        val storage = TvBackupStorage(files)
        files.put("HikViewer-backup-20261101-0900.json", "old", Instant.parse("2026-11-01T09:00:00Z"))
        files.put("notes.txt", "x", Instant.parse("2026-11-09T00:00:00Z"))
        files.put("HikViewer-backup-test.json", "pushed", Instant.parse("2026-11-07T12:00:00Z"))
        files.put("other-backup-20261108.json", "x", Instant.parse("2026-11-09T00:00:00Z"))
        files.now = Instant.parse("2026-11-08T10:00:00Z")
        val written = storage.write("HikViewer-backup-20261108-1000.json", "{}")
        assertThat(written.location).isEqualTo("/sdcard/Download/HikViewer-backup-20261108-1000.json")
        assertThat(storage.list().map { it.name })
            .containsExactly(
                "HikViewer-backup-20261108-1000.json",
                "HikViewer-backup-test.json",
                "HikViewer-backup-20261101-0900.json",
            ).inOrder()
        assertThat(storage.read(storage.list()[1])).isEqualTo("pushed")
    }

    @Test
    fun `a TV entry with no date sorts last and the write error is an IOException`() {
        val files = FakeBackupFiles()
        files.put("HikViewer-backup-b.json", "b", Instant.parse("2026-11-01T09:00:00Z"))
        files.contents["HikViewer-backup-a.json"] = "a" // no modification time recorded
        assertThat(TvBackupStorage(files).list().map { it.name })
            .containsExactly("HikViewer-backup-b.json", "HikViewer-backup-a.json")
            .inOrder()
        files.failWrites = true
        assertThrows(IOException::class.java) { TvBackupStorage(files).write("HikViewer-backup-c.json", "c") }
    }

    @Test
    fun `the app directory files write, list with dates and read back`() {
        val dir = createTempDirectory("hv-backup").toFile()
        val files = AppDirBackupFiles(dir.resolve("deeper"))
        val entry = files.write("HikViewer-backup-20261108-1200.json", "{\"a\":1}")
        assertThat(entry.location).isEqualTo(dir.resolve("deeper/HikViewer-backup-20261108-1200.json").absolutePath)
        assertThat(entry.modified).isNotNull()
        assertThat(files.list().map { it.name }).containsExactly("HikViewer-backup-20261108-1200.json")
        assertThat(files.read(entry)).isEqualTo("{\"a\":1}")
        assertThat(files.location).isEqualTo(dir.resolve("deeper").absolutePath + "/")
        dir.deleteRecursively()
    }

    @Test
    fun `the SAF storage writes and reads the Uri it is handed and names it from the provider or the path`() {
        val uri = Uri.parse("content://com.example.documents/document/42")
        val resolver = shadowOf(context.contentResolver)
        val out = ByteArrayOutputStream()
        resolver.registerOutputStream(uri, out)
        resolver.registerInputStream(uri, ByteArrayInputStream("{\"in\":true}".toByteArray()))
        val storage = SafBackupStorage(context.contentResolver)
        storage.write(uri, "{\"out\":true}")
        assertThat(out.toString()).isEqualTo("{\"out\":true}")
        assertThat(storage.read(uri)).isEqualTo("{\"in\":true}")
        assertThat(storage.displayName(uri)).isEqualTo("42")
    }

    @Test
    fun `the SAF storage stops reading past 1 MiB and turns a refused Uri into an IOException`() {
        val resolver = shadowOf(context.contentResolver)
        val huge = Uri.parse("content://com.example.documents/document/video")
        val endless = EndlessInputStream(limit = 4 shl 20)
        resolver.registerInputStreamSupplier(huge) { endless }
        val storage = SafBackupStorage(context.contentResolver)
        val tooLarge = assertThrows(IOException::class.java) { storage.read(huge) }
        assertThat(tooLarge.message).contains("larger than 1 MiB")
        assertThat(endless.served).isAtMost(BackupText.MAX_BYTES + EndlessInputStream.CHUNK)
        assertThat(endless.closed).isTrue()

        val refused = Uri.parse("content://com.example.documents/document/refused")
        resolver.registerInputStreamSupplier(refused) { throw SecurityException("no grant") }
        resolver.registerOutputStreamSupplier(refused) { throw SecurityException("no grant") }
        assertThat(assertThrows(IOException::class.java) { storage.read(refused) }.message).isEqualTo("no grant")
        assertThat(assertThrows(IOException::class.java) { storage.write(refused, "{}") }.message).isEqualTo("no grant")
        // The name is only a label, so a refused query falls back to the Uri's own.
        assertThat(storage.displayName(refused)).isEqualTo("refused")
    }

    @Test
    fun `the app directory refuses a file past 1 MiB before reading it`() {
        val dir = createTempDirectory("hv-backup").toFile()
        val files = AppDirBackupFiles(dir)
        val big = dir.resolve("HikViewer-backup-20261108-1200.json")
        big.writeBytes(ByteArray(BackupText.MAX_BYTES + 1))
        val entry = files.list().single()
        assertThat(assertThrows(IOException::class.java) { files.read(entry) }.message).contains("larger than 1 MiB")
        val exact = dir.resolve("HikViewer-backup-20261108-1201.json")
        exact.writeBytes(ByteArray(BackupText.MAX_BYTES) { 'a'.code.toByte() })
        assertThat(files.read(files.list().first { it.name == exact.name }).length).isEqualTo(BackupText.MAX_BYTES)
        dir.deleteRecursively()
    }

    /**
     * The Downloads collection through a fake `media` provider: Robolectric has no MediaStore, but
     * the shadow resolver routes insert, query, update and delete to a registered provider and the
     * streams to registered ones, which is enough to see the protocol the real provider expects.
     */
    @Test
    @Config(sdk = [34])
    fun `the Downloads files insert pending, finish, read back the stored name, and drop the row of a failed write`() {
        val provider =
            Robolectric.buildContentProvider(FakeMediaProvider::class.java).create(MediaStore.AUTHORITY).get()
        val resolver = shadowOf(context.contentResolver)
        val out = ByteArrayOutputStream()
        resolver.registerOutputStream(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, 1), out)
        val files = DownloadsBackupFiles(context.contentResolver)
        // A file of that name is already there (adb left it, no date); MediaStore will rename ours.
        provider.rows[10L] =
            ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "HikViewer-backup-20261108-1200.json") }

        val entry = files.write("HikViewer-backup-20261108-1200.json", "{\"a\":1}")
        assertThat(out.toString()).isEqualTo("{\"a\":1}")
        val row = provider.rows.getValue(1L)
        assertThat(row.getAsString(MediaStore.Downloads.RELATIVE_PATH)).isEqualTo("Download")
        assertThat(row.getAsString(MediaStore.Downloads.MIME_TYPE)).isEqualTo("application/json")
        assertThat(provider.pendingHistory).containsExactly(1, 0).inOrder()
        // The provider renamed the file (the name was taken); the entry says what is on disk.
        assertThat(entry.name).isEqualTo("HikViewer-backup-20261108-1200 (1).json")
        assertThat(entry.location).endsWith("/Download/HikViewer-backup-20261108-1200 (1).json")
        assertThat(files.location).endsWith("/Download/")

        val second = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, 2)
        resolver.registerOutputStreamSupplier(second) { throw SecurityException("volume gone") }
        val failed = assertThrows(IOException::class.java) { files.write("HikViewer-backup-20261108-1201.json", "{}") }
        assertThat(failed.message).isEqualTo("volume gone")
        assertThat(provider.rows.keys).containsExactly(10L, 1L)

        provider.rows[3L] = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "other.json") }
        val listed = files.list()
        assertThat(provider.lastSelection).isEqualTo("_display_name LIKE ?")
        assertThat(provider.lastSelectionArgs).asList().containsExactly("HikViewer-backup-%.json")
        assertThat(listed.map { it.name })
            .containsExactly("HikViewer-backup-20261108-1200.json", "HikViewer-backup-20261108-1200 (1).json")
        val ours = listed.first { it.uri == entry.uri }
        assertThat(ours.modified).isEqualTo(Instant.ofEpochSecond(FakeMediaProvider.MODIFIED))
        assertThat(listed.first { it.uri != entry.uri }.modified).isNull()
        assertThat(files.owns(ours)).isTrue()
        resolver.registerInputStream(ours.uri, ByteArrayInputStream("{\"a\":1}".toByteArray()))
        assertThat(files.read(ours)).isEqualTo("{\"a\":1}")
    }

    private fun plantDocumentsUi(
        handlerPackage: String = "com.android.documentsui",
        handlerClass: String = "com.android.documentsui.picker.PickActivity",
    ) {
        val info =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = handlerPackage
                        name = handlerClass
                    }
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(BackupStorage.documentsUiIntent(), info)
    }
}
