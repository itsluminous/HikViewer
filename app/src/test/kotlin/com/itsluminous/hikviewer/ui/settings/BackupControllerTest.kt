package com.itsluminous.hikviewer.ui.settings

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.data.backup.BackupCodec
import com.itsluminous.hikviewer.data.backup.BackupStorage
import com.itsluminous.hikviewer.data.backup.TvBackupStorage
import com.itsluminous.hikviewer.fakes.FakeBackupFiles
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The Backup and Restore rows. A phone has a documents UI (planted in the shadow package
 * manager), so the rows launch `CreateDocument` and `OpenDocument`; a television has none and
 * uses the TV storage, here on a [FakeBackupFiles] directory.
 */
@RunWith(AndroidJUnit4::class)
class BackupControllerTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud = FakeCloudServices(cameraCount = 4, signalCount = 3)
    private val serial = FakeCloudServices.SERIAL
    private val files = FakeBackupFiles()

    @Before
    fun setUp() {
        app.graph = TestGraph.create(app, cloud, backupStorage = { TvBackupStorage(files) })
        runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
    }

    private fun usePhoneWithDocumentsUi() {
        app.graph = TestGraph.create(app, cloud)
        runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
        val info =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = "com.android.documentsui"
                        name = "com.android.documentsui.picker.PickActivity"
                    }
            }
        shadowOf(app.packageManager).addResolveInfoForIntent(BackupStorage.documentsUiIntent(), info)
    }

    private fun latestDialog(): AlertDialog {
        ShadowLooper.idleMainLooper()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun AlertDialog.message(): String = findViewById<TextView>(android.R.id.message)!!.text.toString()

    private fun AlertDialog.ok() {
        getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
    }

    private fun ActivityScenario<SettingsActivity>.settled(block: (SettingsActivity) -> Unit) {
        ShadowLooper.idleMainLooper()
        onActivity(block)
    }

    private fun backupJson(
        showFrameRate: Boolean,
        visible: List<String>,
    ): String =
        """
        {"format":1,"app":"HikViewer","version":"0.5","created":"2026-11-08T15:00:00+05:30",
         "settings":{"visibleCameras":${visible.joinToString(",", "[", "]") { "\"$it\"" }},
                     "fullscreenQuality":"MAIN","showCameraNames":false,"showFrameRate":$showFrameRate,"keepScreenOn":false},
         "arrangements":{"lastLayout":"GRID_3X3","lastLayoutPortrait":"AUTO","orders":{"GRID_3X3":["$serial/3"]}}}
        """.trimIndent()

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `on a phone Backup explains, then launches CreateDocument with the suggested name and writes the Uri`() {
        usePhoneWithDocumentsUi()
        val uri = Uri.parse("content://com.android.providers.downloads.documents/document/raw%3Abackup.json")
        val out = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(uri, out)
        app.graph.settingsStore.showFrameRate = true
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.settled { a ->
                a.findViewById<android.view.View>(R.id.backup).performClick()
                val explain = latestDialog()
                assertThat(explain.message()).contains("Login and encryption password are not included")
                explain.ok()
                val request = shadowOf(a).nextStartedActivityForResult!!.intent
                assertThat(request.action).isEqualTo(Intent.ACTION_CREATE_DOCUMENT)
                assertThat(request.type).isEqualTo("application/json")
                assertThat(request.getStringExtra(Intent.EXTRA_TITLE))
                    .matches("HikViewer-backup-\\d{8}-\\d{4}\\.json")
                shadowOf(a).receiveResult(request, Activity.RESULT_OK, Intent().setData(uri))
                ShadowLooper.idleMainLooper()
                val written = out.toString()
                assertThat(written).contains("\"format\": 1")
                assertThat(written).contains("\"showFrameRate\": true")
                assertThat(written).doesNotContain("secret")
                assertThat(latestDialog().message()).contains("Saved as")
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `on a phone Restore opens a document, applies it to both stores and rebinds the screen`() {
        usePhoneWithDocumentsUi()
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fb.json")
        shadowOf(app.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(backupJson(true, listOf("$serial/3")).toByteArray()))
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.settled { a ->
                assertThat(a.findViewById<SwitchCompat>(R.id.show_fps).isChecked).isFalse()
                a.findViewById<android.view.View>(R.id.restore).performClick()
                val explain = latestDialog()
                assertThat(explain.message()).contains("Login and encryption password are not included")
                explain.ok()
                val request = shadowOf(a).nextStartedActivityForResult!!.intent
                assertThat(request.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT)
                assertThat(request.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList())
                    .containsExactly("application/json", "*/*")
                    .inOrder()
                shadowOf(a).receiveResult(request, Activity.RESULT_OK, Intent().setData(uri))
                ShadowLooper.idleMainLooper()
                assertThat(app.graph.settingsStore.showFrameRate).isTrue()
                assertThat(app.graph.settingsStore.fullscreenQuality).isEqualTo(StreamQuality.MAIN)
                assertThat(app.graph.settingsStore.visibleCameraKeys).containsExactly("$serial/3")
                assertThat(app.graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.GRID_3X3)
                assertThat(app.graph.arrangementStore.order(GridLayoutMode.GRID_3X3)).containsExactly("$serial/3")
                assertThat(app.graph.settingsStore.verificationCode).isEqualTo(FakeCloudServices.GOOD_CODE)
                assertThat(app.graph.account.isLoggedIn).isTrue()
                // The screen shows the restored values without being reopened.
                assertThat(a.findViewById<SwitchCompat>(R.id.show_fps).isChecked).isTrue()
                assertThat(a.findViewById<SwitchCompat>(R.id.quality_main).isChecked).isTrue()
                val list = a.findViewById<RecyclerView>(R.id.camera_list)
                assertThat(
                    (0 until list.childCount).map { list.getChildAt(it).findViewById<CheckBox>(R.id.check).isChecked },
                ).containsExactly(true, false, false, false)
                    .inOrder()
                assertThat(latestDialog().message()).contains("are in force")
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `on a phone a malformed document is refused and nothing changes`() {
        usePhoneWithDocumentsUi()
        val uri = Uri.parse("content://x/doc/1")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream("{\"format\":7}".toByteArray()))
        app.graph.settingsStore.showCameraNames = true
        val before = app.graph.settingsStore.choices()
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.settled { a ->
                a.findViewById<android.view.View>(R.id.restore).performClick()
                latestDialog().ok()
                val request = shadowOf(a).nextStartedActivityForResult!!.intent
                shadowOf(a).receiveResult(request, Activity.RESULT_OK, Intent().setData(uri))
                ShadowLooper.idleMainLooper()
                assertThat(latestDialog().message()).startsWith("Nothing was changed.")
                assertThat(app.graph.settingsStore.choices()).isEqualTo(before)
                assertThat(a.findViewById<SwitchCompat>(R.id.show_names).isChecked).isTrue()
            }
        }
    }

    @Test
    @Config(qualifiers = "television")
    fun `on a television Backup writes to the directory at once and names the path`() {
        app.graph.settingsStore.showCameraNames = true
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.settled { a ->
                a.findViewById<android.view.View>(R.id.backup).performClick()
                val explain = latestDialog()
                assertThat(explain.message()).contains("/sdcard/Download/")
                assertThat(explain.message()).contains("Login and encryption password are not included")
                explain.ok()
                assertThat(shadowOf(a).nextStartedActivityForResult).isNull()
                assertThat(files.contents.keys).hasSize(1)
                val name = files.contents.keys.single()
                assertThat(name).matches("HikViewer-backup-\\d{8}-\\d{4}\\.json")
                assertThat(files.contents[name]).contains("\"showCameraNames\": true")
                val done = latestDialog()
                assertThat(done.message()).isEqualTo("Saved as /sdcard/Download/$name")
            }
        }
    }

    @Test
    @Config(qualifiers = "television")
    fun `on a television Restore lists the backups newest first, with their dates, and applies the chosen one`() {
        files.put(
            "HikViewer-backup-20261101-0900.json",
            backupJson(false, listOf("$serial/1")),
            java.time.Instant.parse("2026-11-01T09:00:00Z"),
        )
        files.put(
            "HikViewer-backup-test.json",
            backupJson(true, listOf("$serial/2")),
            java.time.Instant.parse("2026-11-07T12:00:00Z"),
        )
        files.put("unrelated.json", "{}", java.time.Instant.parse("2026-11-09T12:00:00Z"))
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.settled { a ->
                a.findViewById<android.view.View>(R.id.restore).performClick()
                latestDialog().ok()
                val pick = latestDialog()
                val labels =
                    (0 until pick.listView.adapter.count).map {
                        pick.listView.adapter
                            .getItem(it)
                            .toString()
                    }
                assertThat(labels).hasSize(2)
                assertThat(labels[0]).startsWith("HikViewer-backup-test.json\n")
                assertThat(labels[0]).contains("2026")
                assertThat(labels[1]).startsWith("HikViewer-backup-20261101-0900.json\n")
                // The newest is preselected; take it.
                pick.ok()
                assertThat(app.graph.settingsStore.showFrameRate).isTrue()
                assertThat(app.graph.settingsStore.visibleCameraKeys).containsExactly("$serial/2")
                assertThat(a.findViewById<SwitchCompat>(R.id.show_fps).isChecked).isTrue()
                assertThat(latestDialog().message()).contains("HikViewer-backup-test.json")
            }
        }
    }

    @Test
    @Config(qualifiers = "television")
    fun `on a television with no backup Restore says where to push one`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.settled { a ->
                a.findViewById<android.view.View>(R.id.restore).performClick()
                latestDialog().ok()
                val empty = latestDialog()
                assertThat(empty.message()).contains("/sdcard/Download/")
                assertThat(empty.message()).contains("adb push")
                assertThat(empty.message()).contains(BackupCodec.FILE_PREFIX)
            }
        }
    }

    @Test
    @Config(qualifiers = "television")
    fun `on a television a failed write is reported`() {
        files.failWrites = true
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.settled { a ->
                a.findViewById<android.view.View>(R.id.backup).performClick()
                latestDialog().ok()
                assertThat(latestDialog().message()).isEqualTo("Could not write the backup: disk full")
            }
        }
    }
}
