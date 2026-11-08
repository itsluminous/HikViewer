package com.itsluminous.hikviewer.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.data.backup.BackupCodec
import com.itsluminous.hikviewer.data.backup.BackupStorage
import com.itsluminous.hikviewer.data.backup.TvBackupStorage
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.settings.SettingsActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Settings > Backup and Restore on a device. On the television emulator there is no documents
 * UI, so Backup writes into the Downloads collection and Restore lists what is there; on the
 * phone emulator the rows launch the documents UI, which Espresso-Intents answers with a file
 * of the test's own. Each test skips itself on the other kind of device.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private lateinit var scenario: ActivityScenario<SettingsActivity>
    private val createdOnTv = mutableListOf<Uri>()

    @Before
    fun loggedInGraph() {
        app.graph = TestGraph.create(app, FakeCloudServices(cameraCount = 6, signalCount = 4))
        runBlocking {
            app.graph.account.login("911234567890", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        createdOnTv.forEach { app.contentResolver.delete(it, null, null) }
        finishAllActivities()
    }

    private fun dialogButton(textRes: Int) {
        onView(withText(textRes)).inRoot(isDialog()).perform(click())
    }

    private fun dialogMessage(part: String) {
        onView(withId(android.R.id.message)).inRoot(isDialog()).check(matches(withText(containsString(part))))
    }

    /** Backup lands in Downloads, the confirmation names the path, Restore lists it and puts the old choices back. */
    @Test
    fun tvBackupWritesToDownloadsAndRestoreListsAndAppliesIt() {
        assumeTrue("television only", FormFactor.isTelevision(app))
        assumeFalse("this television has a documents UI", BackupStorage.hasDocumentsUi(app))
        val tv = TvBackupStorage.forDevice(app)
        val before = tv.list().map { it.name }.toSet()
        app.graph.settingsStore.showCameraNames = true
        app.graph.settingsStore.fullscreenQuality = StreamQuality.MAIN
        scenario = ActivityScenario.launch(SettingsActivity::class.java)

        onView(withId(R.id.backup)).perform(scrollTo(), click())
        dialogMessage("Login and encryption password are not included")
        dialogMessage(tv.location)
        dialogButton(R.string.backup_action)
        dialogMessage("Saved as ${tv.location}${BackupCodec.FILE_PREFIX}")
        dialogButton(android.R.string.ok)
        val written = tv.list().filter { it.name !in before }
        assert(written.size == 1) { "expected one new backup in ${tv.location}, found ${written.map { it.name }}" }
        createdOnTv += written.single().uri
        assert(tv.read(written.single()).contains("\"showCameraNames\": true"))

        // A file put into the app directory from outside (what adb push does) is listed too, after the newer one.
        val pushed = File(tv.dropLocation, "HikViewer-backup-pushed.json")
        pushed.parentFile?.mkdirs()
        pushed.writeText(tv.read(written.single()).replace("\"showFrameRate\": false", "\"showFrameRate\": true"))
        pushed.setLastModified(System.currentTimeMillis() - 60_000)
        try {
            // Change two choices, then restore: both come back.
            onView(withId(R.id.show_names)).perform(scrollTo(), click()).check(matches(isNotChecked()))
            onView(withId(R.id.quality_main)).perform(scrollTo(), click()).check(matches(isNotChecked()))
            assert(!app.graph.settingsStore.showCameraNames)
            onView(withId(R.id.restore)).perform(scrollTo(), click())
            dialogMessage("Login and encryption password are not included")
            dialogButton(R.string.restore_action)
            onView(withText(startsWith(written.single().name))).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(startsWith(pushed.name))).inRoot(isDialog()).check(matches(isDisplayed()))
            assert(tv.list().map { it.name }.take(2) == listOf(written.single().name, pushed.name)) { tv.list() }
            dialogButton(R.string.restore_action)
        } finally {
            pushed.delete()
        }
        dialogMessage("are in force")
        dialogButton(android.R.string.ok)
        assert(app.graph.settingsStore.showCameraNames)
        assert(app.graph.settingsStore.fullscreenQuality == StreamQuality.MAIN)
        onView(withId(R.id.show_names)).check(matches(isChecked()))
        onView(withId(R.id.quality_main)).check(matches(isChecked()))
        scenario.onActivity { a -> assert(a.findViewById<SwitchCompat>(R.id.show_names).isChecked) }
    }

    /** The rows launch the documents UI with the right intents; the file it hands back is written, then read. */
    @Test
    fun phoneBackupLaunchesCreateDocumentAndRestoreReadsTheReturnedUri() {
        assumeFalse("phone only", FormFactor.isTelevision(app))
        assumeTrue("this phone has no documents UI", BackupStorage.hasDocumentsUi(app))
        val file = File(app.cacheDir, "HikViewer-backup-espresso.json")
        file.delete()
        val uri = Uri.fromFile(file)
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
            app.graph.settingsStore.showFrameRate = true
            scenario = ActivityScenario.launch(SettingsActivity::class.java)

            onView(withId(R.id.backup)).perform(scrollTo(), click())
            dialogMessage("Login and encryption password are not included")
            dialogButton(R.string.backup_action)
            intended(
                allOf(
                    hasAction(Intent.ACTION_CREATE_DOCUMENT),
                    hasType(BackupCodec.MIME_TYPE),
                    hasExtra(equalTo(Intent.EXTRA_TITLE), startsWith(BackupCodec.FILE_PREFIX)),
                ),
            )
            dialogMessage("Saved as")
            dialogButton(android.R.string.ok)
            assert(file.readText().contains("\"showFrameRate\": true")) { file.readText() }

            onView(withId(R.id.show_fps)).perform(scrollTo(), click()).check(matches(isNotChecked()))
            assert(!app.graph.settingsStore.showFrameRate)
            onView(withId(R.id.restore)).perform(scrollTo(), click())
            dialogButton(R.string.restore_action)
            intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            dialogMessage("are in force")
            dialogButton(android.R.string.ok)
            assert(app.graph.settingsStore.showFrameRate)
            onView(withId(R.id.show_fps)).check(matches(isChecked()))
        } finally {
            Intents.release()
            file.delete()
        }
    }
}
