package com.itsluminous.hikviewer.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.auth.Session
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.data.GridArrangementStore
import com.itsluminous.hikviewer.data.NamePosition
import com.itsluminous.hikviewer.data.SessionStore
import com.itsluminous.hikviewer.data.SettingsStore
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class BackupCodecTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("backup-test", 0).also { it.edit().clear().commit() }
    private val sessionPrefs = context.getSharedPreferences("backup-session", 0).also { it.edit().clear().commit() }
    private val settings = SettingsStore(prefs)
    private val arrangement = GridArrangementStore(prefs)
    private val sessionStore = SessionStore(sessionPrefs)
    private val now = ZonedDateTime.of(2026, 11, 8, 15, 42, 7, 0, ZoneOffset.ofHoursMinutes(5, 30))
    private val codec = BackupCodec(settings, arrangement, appVersion = "0.5", now = { now })

    /** Every field set to something other than its default, so a round trip proves each one travels. */
    private fun fillStores() {
        settings.visibleCameraKeys = listOf("S1/3", "S1/1", "S2/7")
        settings.fullscreenQuality = StreamQuality.MAIN
        settings.showCameraNames = true
        settings.showFrameRate = true
        settings.keepScreenOn = true
        settings.cameraNamePosition = NamePosition.TOP_LEFT
        settings.verificationCode = "ABCDEF"
        arrangement.lastLayout = GridLayoutMode.GRID_4X3
        arrangement.lastLayoutPortrait = GridLayoutMode.GRID_2X7
        arrangement.setOrder(GridLayoutMode.GRID_4X3, listOf("S1/1", "S1/3"))
        arrangement.setOrder(GridLayoutMode.HERO_1_5, listOf("S2/7", "S1/1", "S1/3"))
        sessionStore.save(Session("sid", "rid", "api.example", "u1", 7))
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `encode writes the versioned envelope, every choice and every arrangement, and no secret`() {
        fillStores()
        val text = codec.encode()
        val doc = json(text)
        assertThat(doc["format"]!!.jsonPrimitive.content).isEqualTo("1")
        assertThat(doc["app"]!!.jsonPrimitive.content).isEqualTo("HikViewer")
        assertThat(doc["version"]!!.jsonPrimitive.content).isEqualTo("0.5")
        assertThat(doc["created"]!!.jsonPrimitive.content).isEqualTo("2026-11-08T15:42:07+05:30")
        val s = doc["settings"]!!.jsonObject
        assertThat(s.keys).containsExactly(
            "visibleCameras",
            "fullscreenQuality",
            "showCameraNames",
            "showFrameRate",
            "keepScreenOn",
            "cameraNamePosition",
        )
        assertThat(s["fullscreenQuality"]!!.jsonPrimitive.content).isEqualTo("MAIN")
        assertThat(s["cameraNamePosition"]!!.jsonPrimitive.content).isEqualTo("TOP_LEFT")
        val a = doc["arrangements"]!!.jsonObject
        assertThat(a["lastLayout"]!!.jsonPrimitive.content).isEqualTo("GRID_4X3")
        assertThat(a["lastLayoutPortrait"]!!.jsonPrimitive.content).isEqualTo("GRID_2X7")
        assertThat(a["orders"]!!.jsonObject.keys).containsExactly("GRID_4X3", "HERO_1_5")
        // Nothing secret: not the code, not the session, not the account.
        assertThat(text).doesNotContain("ABCDEF")
        assertThat(text).doesNotContain("sid")
        assertThat(text).doesNotContain("verificationCode")
        assertThat(text).doesNotContain("session")
    }

    @Test
    fun `a backup restores every field into empty stores`() {
        fillStores()
        val text = codec.encode()
        val before = codec.document()
        prefs.edit().clear().commit()
        assertThat(settings.visibleCameraKeys).isNull()
        assertThat(arrangement.lastLayout).isEqualTo(GridLayoutMode.AUTO)
        codec.restore(text)
        assertThat(settings.choices()).isEqualTo(
            SettingsStore.Choices(
                listOf("S1/3", "S1/1", "S2/7"),
                StreamQuality.MAIN,
                true,
                true,
                true,
                NamePosition.TOP_LEFT,
            ),
        )
        assertThat(arrangement.snapshot()).isEqualTo(
            GridArrangementStore.Snapshot(
                GridLayoutMode.GRID_4X3,
                GridLayoutMode.GRID_2X7,
                mapOf(
                    GridLayoutMode.GRID_4X3 to listOf("S1/1", "S1/3"),
                    GridLayoutMode.HERO_1_5 to listOf("S2/7", "S1/1", "S1/3"),
                ),
            ),
        )
        assertThat(codec.document().copy(created = before.created)).isEqualTo(before)
    }

    @Test
    fun `restore replaces what is there, removing orders the backup lacks`() {
        fillStores()
        val text = codec.encode()
        arrangement.setOrder(GridLayoutMode.GRID_3X3, listOf("S9/9"))
        settings.visibleCameraKeys = listOf("S9/9")
        settings.showFrameRate = false
        codec.restore(text)
        assertThat(arrangement.order(GridLayoutMode.GRID_3X3)).isNull()
        assertThat(settings.visibleCameraKeys).containsExactly("S1/3", "S1/1", "S2/7").inOrder()
        assertThat(settings.showFrameRate).isTrue()
    }

    @Test
    fun `restore leaves the verification code and the session alone`() {
        fillStores()
        val text = codec.encode()
        settings.verificationCode = "OTHER1"
        sessionStore.save(Session("sid2", "rid2", "api2.example", "u2", 8))
        codec.restore(text)
        assertThat(settings.verificationCode).isEqualTo("OTHER1")
        assertThat(sessionStore.session()).isEqualTo(Session("sid2", "rid2", "api2.example", "u2", 8))
        // A backup with no camera list restores "never chose" as null, not as an empty list.
        settings.visibleCameraKeys = null
        codec.restore(codec.encode())
        assertThat(settings.visibleCameraKeys).isNull()
    }

    @Test
    fun `unknown fields anywhere are ignored and unknown camera keys are kept`() {
        val text =
            """
            {"format":1,"app":"HikViewer","version":"9.9","created":"2030-01-01T00:00:00Z","future":{"x":1},
             "settings":{"visibleCameras":["Z9/1"],"fullscreenQuality":"SUB","showCameraNames":false,
                         "showFrameRate":true,"keepScreenOn":false,"newToggle":true},
             "arrangements":{"lastLayout":"AUTO","lastLayoutPortrait":"GRID_1X3",
                             "orders":{"GRID_1X3":["Z9/1","Z9/2"]},"heroColours":[]}}
            """.trimIndent()
        settings.cameraNamePosition = NamePosition.BOTTOM_RIGHT
        codec.restore(text)
        assertThat(settings.visibleCameraKeys).containsExactly("Z9/1")
        assertThat(settings.showFrameRate).isTrue()
        assertThat(arrangement.lastLayoutPortrait).isEqualTo(GridLayoutMode.GRID_1X3)
        assertThat(arrangement.order(GridLayoutMode.GRID_1X3)).containsExactly("Z9/1", "Z9/2").inOrder()
        // A format-1 file from before the name position existed: the default corner, not a refusal.
        assertThat(settings.cameraNamePosition).isEqualTo(NamePosition.BOTTOM_LEFT)
    }

    @Test
    fun `an unknown name position restores as the default while a known one is applied`() {
        fun file(position: String) =
            """
            {"format":1,"app":"HikViewer","version":"0.9","created":"2030-01-01T00:00:00Z",
             "settings":{"visibleCameras":null,"fullscreenQuality":"SUB","showCameraNames":true,
                         "showFrameRate":false,"keepScreenOn":false,"cameraNamePosition":"$position"},
             "arrangements":{"lastLayout":"AUTO","lastLayoutPortrait":"AUTO","orders":{}}}
            """.trimIndent()
        codec.restore(file("BOTTOM_RIGHT"))
        assertThat(settings.cameraNamePosition).isEqualTo(NamePosition.BOTTOM_RIGHT)
        codec.restore(file("TOP_CENTRE"))
        assertThat(settings.cameraNamePosition).isEqualTo(NamePosition.BOTTOM_LEFT)
        assertThat(settings.showCameraNames).isTrue()
    }

    private fun rejects(
        text: String,
        vararg messageParts: String,
    ) {
        fillStores()
        val before = prefs.all.toMap()
        val e = assertThrows(BackupFormatException::class.java) { codec.restore(text) }
        messageParts.forEach { assertThat(e.message).contains(it) }
        assertThat(prefs.all).isEqualTo(before)
    }

    private fun valid(): String = codec.also { fillStores() }.encode()

    @Test
    fun `malformed input is rejected and changes nothing`() {
        rejects("not json at all", "Not a HikViewer backup file")
        rejects("""{"format":1}""", "Not a HikViewer backup file")
        rejects("""[1,2,3]""", "Not a HikViewer backup file")
        rejects("", "Not a HikViewer backup file")
    }

    @Test
    fun `another app's file and other formats are rejected`() {
        rejects(
            valid().replace("\"app\": \"HikViewer\"", "\"app\": \"ClearSMS\""),
            "Not a HikViewer backup file",
            "ClearSMS",
        )
        rejects(valid().replace("\"format\": 1", "\"format\": 0"), "format 0", "reads format 1")
        rejects(valid().replace("\"format\": 1", "\"format\": 2"), "format 2", "written by HikViewer 0.5")
    }

    @Test
    fun `an unknown quality or layout name is rejected before anything is written`() {
        rejects(valid().replace("\"MAIN\"", "\"ULTRA\""), "Unknown fullscreen quality: ULTRA")
        rejects(valid().replace("\"GRID_2X7\"", "\"GRID_9X9\""), "Unknown grid layout: GRID_9X9")
        rejects(valid().replace("\"HERO_1_5\"", "\"HERO_1_99\""), "Unknown grid layout: HERO_1_99")
    }

    @Test
    fun `the suggested file name carries the minute and the restore list recognises it`() {
        assertThat(codec.suggestedFileName()).isEqualTo("HikViewer-backup-20261108-1542.json")
        assertThat(BackupCodec.isBackupFileName("HikViewer-backup-20261108-1542.json")).isTrue()
        assertThat(BackupCodec.isBackupFileName("HikViewer-backup-test.json")).isTrue()
        assertThat(BackupCodec.isBackupFileName("HikViewer-backup-.json")).isFalse()
        assertThat(BackupCodec.isBackupFileName("notes.json")).isFalse()
        assertThat(BackupCodec.isBackupFileName("HikViewer-backup-x.txt")).isFalse()
    }
}
