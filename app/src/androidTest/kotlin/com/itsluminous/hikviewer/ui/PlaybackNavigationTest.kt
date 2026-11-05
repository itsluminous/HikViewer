package com.itsluminous.hikviewer.ui

import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.hikviewer.EspressoApp
import com.itsluminous.hikviewer.HikViewerApp
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import com.itsluminous.hikviewer.player.PlaybackFactory
import com.itsluminous.hikviewer.ui.common.FormFactor
import com.itsluminous.hikviewer.ui.grid.GridActivity
import com.itsluminous.hikviewer.ui.grid.TileAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Grid → long press → Playback → controls → Back, on the TV emulator with the fake cloud: the
 * fake answers the record search with two segments and plays the synthetic H.264 clip for each
 * window through the real decoders.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackNavigationTest {
    private val app = ApplicationProvider.getApplicationContext<HikViewerApp>()
    private val cloud = FakeCloudServices(cameraCount = 16, signalCount = 12)
    private lateinit var scenario: ActivityScenario<GridActivity>

    @Before
    fun loggedInGraph() {
        assumeTrue("D-pad suite, television only", FormFactor.isTelevision(app))
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        app.graph = TestGraph.create(app, cloud, EspressoApp.TOP_BAR_HIDE_MILLIS, playbackFactory = PlaybackFactory())
        runBlocking { app.graph.account.login("911234567890", "secret", Region.INDIA) }
        app.graph.settingsStore.verificationCode = FakeCloudServices.GOOD_CODE
        scenario = ActivityScenario.launch(GridActivity::class.java)
    }

    @After
    fun tearDown() {
        // @After runs after a failed assumption too; on a phone the scenario was never launched.
        if (::scenario.isInitialized) scenario.close()
        finishAllActivities()
    }

    private fun dpad(keyCode: Int) = onView(isRoot()).perform(pressKey(keyCode))

    private fun longOk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        waitFor(android.view.ViewConfiguration.getLongPressTimeout() + 300L)
        val up = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, up, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        instrumentation.waitForIdleSync()
    }

    private val account get() = cloud.accounts.single()

    private fun awaitPlaybackOpens(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (account.playbackOpened.size < count && System.currentTimeMillis() < deadline) waitFor(50)
        assert(
            account.playbackOpened.size >= count,
        ) { "expected $count playback windows, got ${account.playbackOpened.size}" }
    }

    @Test
    fun longPressPlaybackOpensTheRecordingAndBackReturnsToTheGrid() {
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        longOk()
        onView(withText(R.string.tile_action_playback)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
        repeat(TileAction.entries.indexOf(TileAction.PLAYBACK)) { dpad(KeyEvent.KEYCODE_DPAD_DOWN) }
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor(500)

        // Any key shows the controls (they may already have auto-hidden on a slow emulator); MENU changes nothing else.
        dpad(KeyEvent.KEYCODE_MENU)
        onView(withId(R.id.time_bar)).check(matches(isDisplayed()))
        onView(withId(R.id.speed_button)).check(matches(withText("1x")))
        awaitPlaybackOpens(1)
        val today = LocalDate.now()
        assert(account.searched.single() == account.playbackOpened.first().first to today)
        assert(
            account.playbackOpened
                .first()
                .first.channelNo == 2,
        )
        assert(
            account.playbackOpened
                .first()
                .second.begin == today.atTime(23, 55),
        )

        // The bar is the active control: RIGHT moves the label at once; the stream reopens 10 s on
        // once the seek debounce has run out.
        val before = timeLabel()
        dpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        assert(timeLabel() != before) { "label did not move on RIGHT" }
        awaitPlaybackOpens(2)
        val reopened = account.playbackOpened[1].second.begin
        assert(!reopened.isBefore(today.atTime(23, 55, 10))) { "reopened at $reopened" }

        // UP → date chip; LEFT → yesterday is listed and played from its first segment.
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        dpad(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor(300)
        onView(withId(R.id.date_chip)).check(matches(withText("◀ ${today.minusDays(1)} ▶")))
        awaitPlaybackOpens(3)
        assert(
            account.playbackOpened
                .last()
                .second.begin == today.minusDays(1).atTime(6, 0),
        )

        // UP → speed button; OK cycles to +2x; OK on the bar pauses.
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        onView(withId(R.id.speed_button)).check(matches(withText("+2x")))
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        dpad(KeyEvent.KEYCODE_DPAD_DOWN)
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        onView(withId(R.id.status)).check(matches(withText(R.string.playback_paused)))

        // Controls auto-hide after the shared idle time and come back on a key.
        waitFor(EspressoApp.TOP_BAR_HIDE_MILLIS * 2)
        onView(withId(R.id.controls)).check(matches(withEffectiveVisibility(Visibility.INVISIBLE)))
        dpad(KeyEvent.KEYCODE_DPAD_UP)
        onView(withId(R.id.controls)).check(matches(isDisplayed()))

        pressBack()
        onView(withId(R.id.grid)).check(matches(isDisplayed()))
    }

    @Test
    fun holdingLeftSeeksInGrowingJumpsAndReopensOnceAfterRelease() {
        longOk()
        repeat(TileAction.entries.indexOf(TileAction.PLAYBACK)) { dpad(KeyEvent.KEYCODE_DPAD_DOWN) }
        dpad(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlaybackOpens(1)
        waitFor(500) // let the first pictures move the playhead before the hold starts
        val opens = account.playbackOpened.size

        // Hold LEFT: a key down, then auto-repeats every 150 ms for 3 s, as a remote sends them. The
        // jumps between consecutive labels grow from 10 s to 30 s to 60 s with the hold time; no
        // reopen happens while repeats keep coming.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = android.os.SystemClock.uptimeMillis()
        val jumps = mutableListOf<Pair<Long, Long>>() // (hold millis, seconds moved)
        var repeatCount = 0
        var last = parseLabel(timeLabel())
        while (android.os.SystemClock.uptimeMillis() - down < 3_000) {
            val now = android.os.SystemClock.uptimeMillis()
            instrumentation.sendKeySync(
                KeyEvent(down, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, repeatCount++),
            )
            instrumentation.waitForIdleSync()
            val label = parseLabel(timeLabel())
            jumps += (now - down) to (last - label)
            last = label
            waitFor(150)
        }
        val up = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, up, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assert(account.playbackOpened.size == opens) { "reopened during the hold: ${account.playbackOpened.size}" }
        val early = jumps.filter { it.first < 900 }.map { it.second }
        val middle = jumps.filter { it.first in 1_100..2_300 }.map { it.second }
        val late = jumps.filter { it.first > 2_700 }.map { it.second }
        assert(early.isNotEmpty() && early.all { it == 10L }) { "first second: $early (all: $jumps)" }
        assert(middle.isNotEmpty() && middle.all { it == 30L }) { "1–2.5 s: $middle (all: $jumps)" }
        assert(late.isNotEmpty() && late.all { it == 60L }) { "after 2.5 s: $late (all: $jumps)" }

        // Released: one reopen at the label, 600 ms later.
        val target = timeLabel()
        awaitPlaybackOpens(opens + 1)
        waitFor(300)
        assert(account.playbackOpened.size == opens + 1) { "more than one reopen: ${account.playbackOpened.size}" }
        val reopened =
            account.playbackOpened
                .last()
                .second.begin
        assert(
            reopened.toLocalTime().toSecondOfDay().toLong() == parseLabel(target),
        ) { "reopened at $reopened for $target" }
        pressBack()
    }

    private fun timeLabel(): String {
        var text = ""
        onView(withId(R.id.time_label)).check { v, _ -> text = (v as android.widget.TextView).text.toString() }
        return text
    }

    /** `HH:mm:ss` → seconds of the day. */
    private fun parseLabel(label: String): Long {
        val (h, m, s) = label.split(':').map { it.toLong() }
        return h * 3600 + m * 60 + s
    }
}
