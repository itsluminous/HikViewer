package com.itsluminous.hikviewer.demo

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.core.stream.StreamQuality
import com.itsluminous.hikviewer.core.stream.StreamStatus
import com.itsluminous.hikviewer.core.stream.VideoCodec
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenActivity
import com.itsluminous.hikviewer.ui.grid.GridActivity
import com.itsluminous.hikviewer.ui.grid.GridLayoutMode
import com.itsluminous.hikviewer.ui.login.LoginActivity
import com.itsluminous.hikviewer.ui.playback.PlaybackActivity
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

// Lives in src/testDebug because DemoActivity exists only in the debug variant.
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class DemoActivityTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()

    /** Runs the demo entry point with [extras] and returns the Intent it launched. */
    private fun launch(vararg extras: Pair<String, Any>): Intent {
        val intent = Intent(app, DemoActivity::class.java)
        extras.forEach { (k, v) ->
            when (v) {
                is Int -> intent.putExtra(k, v)
                is Boolean -> intent.putExtra(k, v)
                else -> intent.putExtra(k, v.toString())
            }
        }
        val controller = Robolectric.buildActivity(DemoActivity::class.java, intent).create()
        assertThat(controller.get().isFinishing).isTrue()
        controller.destroy()
        return checkNotNull(shadowOf(app).nextStartedActivity) { "DemoActivity started nothing" }
    }

    @Test
    fun `grid screen installs a logged-in demo graph with the requested layout and camera count`() {
        val next = launch(DemoActivity.EXTRA_LAYOUT to "GRID_4X3", DemoActivity.EXTRA_CAMERAS to 8)

        assertThat(next.component?.className).isEqualTo(GridActivity::class.java.name)
        assertThat(next.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isNotEqualTo(0)
        val graph = app.graph
        assertThat(graph.cloud).isInstanceOf(DemoCloudServices::class.java)
        assertThat(graph.account.isLoggedIn).isTrue()
        assertThat(graph.arrangementStore.lastLayout).isEqualTo(GridLayoutMode.GRID_4X3)
        assertThat(graph.settingsStore.showCameraNames).isTrue()
        val inventory = runBlocking { graph.account.loadInventory() }
        assertThat(inventory.needsVerificationCode).isFalse()
        assertThat(inventory.cameras.map { it.name })
            .containsExactly(
                "Front Door",
                "Driveway",
                "Garage",
                "Back Garden",
                "Porch",
                "Living Room",
                "Kitchen",
                "Hallway",
            ).inOrder()
        assertThat(inventory.cameras.all { it.hasSignal }).isTrue()
    }

    @Test
    fun `login screen leaves nobody logged in`() {
        val next = launch(DemoActivity.EXTRA_SCREEN to DemoActivity.SCREEN_LOGIN, DemoActivity.EXTRA_NAMES to false)

        assertThat(next.component?.className).isEqualTo(LoginActivity::class.java.name)
        assertThat(app.graph.settingsStore.showCameraNames).isFalse()
        assertThat(app.graph.cloud).isInstanceOf(DemoCloudServices::class.java)
        assertThat(app.graph.account.isLoggedIn).isFalse()
    }

    @Test
    fun `fullscreen and playback carry the tile index and the demo camera key`() {
        val fullscreen =
            launch(DemoActivity.EXTRA_SCREEN to DemoActivity.SCREEN_FULLSCREEN, DemoActivity.EXTRA_INDEX to 3)
        assertThat(fullscreen.component?.className).isEqualTo(FullscreenActivity::class.java.name)
        assertThat(fullscreen.getIntExtra(FullscreenActivity.EXTRA_INDEX, -1)).isEqualTo(3)
        // Those screens take the camera list from the already-loaded inventory.
        assertThat(
            app.graph.account.inventory.value
                ?.cameras,
        ).hasSize(DemoCloudServices.DEFAULT_CAMERA_COUNT)

        val playback = launch(DemoActivity.EXTRA_SCREEN to DemoActivity.SCREEN_PLAYBACK, DemoActivity.EXTRA_INDEX to 1)
        assertThat(playback.component?.className).isEqualTo(PlaybackActivity::class.java.name)
        assertThat(playback.getStringExtra(PlaybackActivity.EXTRA_CAMERA_KEY)).isEqualTo("DEMO0000001/2")
    }

    @Test
    fun `bundled clips decode to H264 pictures that start with a keyframe carrying parameter sets`() {
        launch()
        val inventory = runBlocking { app.graph.account.loadInventory() }
        val statuses = mutableListOf<StreamStatus>()

        val pictures =
            runBlocking {
                app.graph.account
                    .openStream(inventory.cameras[0], StreamQuality.SUB) { statuses += it }
                    .take(30)
                    .toList()
            }

        assertThat(statuses).containsExactly(StreamStatus.Connecting(1), StreamStatus.Streaming).inOrder()
        assertThat(pictures).hasSize(30)
        assertThat(pictures.first().isKeyframe).isTrue()
        assertThat(pictures.first().config).isNotNull()
        assertThat(pictures.all { it.codec == VideoCodec.H264 }).isTrue()
        // 36 pictures per clip, then the loop continues with later presentation times.
        assertThat(pictures.zipWithNext().all { (a, b) -> b.presentationTimeUs!! > a.presentationTimeUs!! }).isTrue()
    }

    /** Twelve clips, one per demo room, named after the camera; the names past them fall back by channel. */
    @Test
    fun `every demo camera name has its own clip and the rest fall back by channel number`() {
        val clips = DemoClips(app.assets)
        assertThat(clips.count).isEqualTo(12)
        val rooms =
            listOf(
                "Back Garden",
                "Driveway",
                "Front Door",
                "Garage",
                "Hallway",
                "Kitchen",
                "Living Room",
                "Nursery",
                "Office",
                "Patio",
                "Porch",
                "Side Gate",
            )
        assertThat(clips.slugs).containsExactlyElementsIn(rooms.map(DemoClips::slug)).inOrder()
        (1..12).forEach { n -> assertThat(clips.slugs).contains(DemoClips.slug(DemoCloudServices.cameraName(n))) }
        assertThat(DemoClips.slug("Front Door")).isEqualTo("front_door")
        assertThat(DemoClips.slug("Camera 17")).isEqualTo("camera_17")
        assertThat(clips.slugs).doesNotContain(DemoClips.slug("Workshop"))

        // Same first picture as the clip the name maps to; a name without a clip uses channel modulo count.
        fun first(
            name: String,
            index: Int,
        ): Int = runBlocking {
            clips
                .stream(name, index, paced = false)
                .take(1)
                .toList()
                .single()
                .data
                .contentHashCode()
        }
        assertThat(first("Front Door", 1)).isEqualTo(first("front door", 99))
        assertThat(first("Front Door", 1)).isNotEqualTo(first("Driveway", 2))
        // 13 mod 12 = 1: the second clip in slug order, driveway.
        assertThat(first("Workshop", 13)).isEqualTo(first("Camera 1", 1))
        assertThat(first("Workshop", 13)).isEqualTo(first("Driveway", 2))
    }

    @Test
    fun `every channel has footage with a mid-day gap and the names run out gracefully`() {
        launch(DemoActivity.EXTRA_CAMERAS to 16)
        val inventory = runBlocking { app.graph.account.loadInventory() }
        val day = LocalDate.of(2026, 1, 15)

        val segments = runBlocking { app.graph.account.searchRecordings(inventory.cameras.last(), day) }
        assertThat(segments).hasSize(2)
        assertThat(segments[0].begin).isEqualTo(day.atStartOfDay())
        assertThat(segments[1].end).isEqualTo(day.plusDays(1).atStartOfDay())
        assertThat(inventory.cameras.last().name).isEqualTo("Balcony")
        assertThat(DemoCloudServices.cameraName(17)).isEqualTo("Camera 17")

        // Today's footage stops at the current minute, so playback opens near "now" like a real recorder.
        val today = runBlocking { app.graph.account.searchRecordings(inventory.cameras[0], LocalDate.now()) }
        assertThat(today).isNotEmpty()
        assertThat(today.last().end).isAtMost(LocalDateTime.now())
        assertThat(today.last().end.second).isEqualTo(0)
    }
}
