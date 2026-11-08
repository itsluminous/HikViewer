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
        assertThat(BackupStorage.hasDocumentsUi(context.packageManager)).isFalse()
        assertThat(BackupStorage.choose(context)).isInstanceOf(TvBackupStorage::class.java)
    }

    /** A phone (or any device) with a documents UI gets the Storage Access Framework, with no permission involved. */
    @Test
    fun `with a documents UI the SAF storage is chosen`() {
        plantDocumentsUi()
        assertThat(BackupStorage.hasDocumentsUi(context.packageManager)).isTrue()
        assertThat(BackupStorage.choose(context)).isInstanceOf(SafBackupStorage::class.java)
    }

    @Test
    @Config(sdk = [28])
    fun `before API 29 the TV storage is the app's external directory`() {
        val storage = BackupStorage.choose(context) as TvBackupStorage
        assertThat(storage.location).isEqualTo(context.getExternalFilesDir(null)!!.absolutePath + "/")
    }

    @Test
    @Config(sdk = [34])
    fun `from API 29 the TV storage is the Downloads collection`() {
        val storage = BackupStorage.choose(context) as TvBackupStorage
        assertThat(storage.location).endsWith("/Download/")
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

    private fun plantDocumentsUi() {
        val info =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = "com.android.documentsui"
                        name = "com.android.documentsui.picker.PickActivity"
                    }
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(BackupStorage.documentsUiIntent(), info)
    }
}
