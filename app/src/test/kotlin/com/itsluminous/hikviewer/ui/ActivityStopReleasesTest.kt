package com.itsluminous.hikviewer.ui

import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.RecordingDecoderFactory
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.TilePlayer
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenActivity
import com.itsluminous.hikviewer.ui.fullscreen.FullscreenViewModel
import com.itsluminous.hikviewer.ui.grid.GridActivity
import com.itsluminous.hikviewer.ui.grid.GridViewModel
import com.itsluminous.hikviewer.ui.phone.PhoneCameraActivity
import com.itsluminous.hikviewer.ui.phone.PhoneCameraViewModel
import com.itsluminous.hikviewer.ui.playback.PlaybackActivity
import com.itsluminous.hikviewer.ui.playback.PlaybackViewModel
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Round-3 item 2: nothing may outlive a stopped screen. Every Activity that streams must leave
 * zero collected stream flows (= zero relay sockets) and zero live decoders behind once it is
 * stopped, so a HOME or BACK leaves a process Android can cache or kill at no cost.
 *
 * The fake account counts the flows being collected; the recording decoder factory remembers
 * every decoder it handed out and whether it was released.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ActivityStopReleasesTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud =
        FakeCloudServices(cameraCount = 6, signalCount = 6).apply {
            // Recorded footage keeps coming at the synthetic frame rate, so a picture still reaches
            // the decoder after the test has handed the player a surface; the window never ends.
            playbackPaced = true
            playbackHold = true
            playbackFramesPerWindow = 600
        }
    private val decoders = RecordingDecoderFactory()
    private val account get() = cloud.accounts.single()

    @Before
    fun setUp() {
        app.graph = TestGraph.create(app, cloud, topBarAutoHideMillis = 300, decoders = decoders)
        kotlinx.coroutines.runBlocking {
            app.graph.account.login("a", "secret", Region.INDIA)
            app.graph.account.loadInventory()
        }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
    }

    /** Streams run on worker threads; pumps the main looper until [condition] holds (max 5 s). */
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertWithMessage(
            "active=${account.activeStreams.get()} opened=${account.opened.size} " +
                "playback=${account.playbackOpened.size} searched=${account.searched.size} " +
                "decoders=${decoders.created.size}",
        ).that(condition()).isTrue()
    }

    /**
     * Robolectric never creates a window surface, so the players get one by hand; a decoder is
     * only created for a tile that has somewhere to draw.
     */
    private fun <A : android.app.Activity> ActivityScenario<A>.stopAndAssertReleased(players: (A) -> List<TilePlayer>) {
        var attached = false
        await {
            onActivity { a ->
                val list = players(a)
                if (list.isNotEmpty() && !attached) {
                    list.forEach { it.attachSurface(Surface(SurfaceTexture(1))) }
                    attached = true
                }
            }
            attached
        }
        // Streams are open and at least one picture reached a decoder.
        await { account.activeStreams.get() > 0 && decoders.created.isNotEmpty() }
        moveToState(Lifecycle.State.CREATED)
        // Cancellation completes on the stream thread, so give it a moment.
        await { account.activeStreams.get() == 0 }
        assertThat(decoders.created.all { it.released }).isTrue()
        // A pool that was fed keyframes must have been told to close its codecs; one that never was
        // (single-stream screens) holds nothing to close.
        assertThat(decoders.pools.all { it.codecsClosed > 0 || it.submitted.isEmpty() }).isTrue()
    }

    @Test
    fun `grid onStop closes every stream and decoder`() {
        ActivityScenario.launch(GridActivity::class.java).use { scenario ->
            scenario.stopAndAssertReleased {
                it
                    .viewModels<GridViewModel>()
                    .value.playback.players.value
            }
            assertThat(account.opened).hasSize(6)
        }
    }

    @Test
    fun `fullscreen onStop closes the stream and decoder`() {
        ActivityScenario.launch<FullscreenActivity>(FullscreenActivity.intent(app, 1)).use { scenario ->
            scenario.stopAndAssertReleased {
                it
                    .viewModels<FullscreenViewModel>()
                    .value.playback.players.value
            }
        }
    }

    @Test
    fun `playback onStop closes the recorded stream and decoder`() {
        val key = "${FakeCloudServices.SERIAL}/2"
        ActivityScenario.launch<PlaybackActivity>(PlaybackActivity.intent(app, key)).use { scenario ->
            scenario.stopAndAssertReleased { listOf(it.viewModels<PlaybackViewModel>().value.player) }
            assertThat(account.playbackOpened).isNotEmpty()
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    fun `phone camera onStop closes live and, after scrubbing, playback`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        ActivityScenario.launch<PhoneCameraActivity>(PhoneCameraActivity.intent(app, 1)).use { scenario ->
            scenario.stopAndAssertReleased {
                it
                    .viewModels<PhoneCameraViewModel>()
                    .value.live.players.value
            }
            assertThat(account.playbackOpened).isEmpty()
            // Back to the foreground: live again, then scrub into footage so playback owns the stream.
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { account.activeStreams.get() > 0 }
            scenario.onActivity { a -> a.viewModels<PhoneCameraViewModel>().value.scrubTo(0.5f) }
            await { account.playbackOpened.isNotEmpty() }
            scenario.moveToState(Lifecycle.State.CREATED)
            await { account.activeStreams.get() == 0 }
            assertThat(decoders.created.all { it.released }).isTrue()
        }
    }
}
