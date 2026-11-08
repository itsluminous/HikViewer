package com.itsluminous.hikviewer.data.backup

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.fakes.FakeBackupFiles
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
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
