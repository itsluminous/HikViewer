package com.itsluminous.hikviewer.ui.grid

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.player.LivenessCounts
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveStatusTextTest {
    private val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private fun text(counts: LivenessCounts) = LiveStatusText.format(resources, counts)

    @Test
    fun `all live needs one number`() {
        assertThat(text(LivenessCounts(cameras = 14, live = 14, atOneFps = 0))).isEqualTo("14 live")
        assertThat(text(LivenessCounts(cameras = 1, live = 1, atOneFps = 0))).isEqualTo("1 live")
    }

    @Test
    fun `mixed grids name the cameras, the live ones and the ones at one fps`() {
        assertThat(
            text(LivenessCounts(cameras = 14, live = 8, atOneFps = 6)),
        ).isEqualTo("14 cameras · 8 live · 6 at 1 fps")
    }

    @Test
    fun `tiles with a red dot are left to their dots`() {
        assertThat(
            text(LivenessCounts(cameras = 14, live = 8, atOneFps = 5)),
        ).isEqualTo("14 cameras · 8 live · 5 at 1 fps")
        assertThat(text(LivenessCounts(cameras = 12, live = 7, atOneFps = 0))).isEqualTo("12 cameras · 7 live")
        assertThat(text(LivenessCounts(cameras = 1, live = 0, atOneFps = 0))).isEqualTo("1 camera · 0 live")
    }

    @Test
    fun `the compact form stacks a number over its word and leaves the camera total out`() {
        val compact = { c: LivenessCounts -> LiveStatusText.compact(resources, c).split("\n") }
        assertThat(compact(LivenessCounts(cameras = 12, live = 12, atOneFps = 0)))
            .containsExactly("12", "live")
            .inOrder()
        assertThat(compact(LivenessCounts(cameras = 16, live = 12, atOneFps = 4)))
            .containsExactly("12", "live", "4", "1 fps")
            .inOrder()
        assertThat(compact(LivenessCounts(cameras = 9, live = 7, atOneFps = 0))).containsExactly("7", "live").inOrder()
        // No line is wider than the bar's icons: "1 fps" is the longest word, two digits the longest number.
        compact(LivenessCounts(cameras = 16, live = 10, atOneFps = 6)).forEach { assertThat(it.length).isAtMost(5) }
    }

    @Test
    fun `an empty grid is not all live`() {
        assertThat(text(LivenessCounts(cameras = 0, live = 0, atOneFps = 0))).isEqualTo("0 cameras · 0 live")
    }
}
