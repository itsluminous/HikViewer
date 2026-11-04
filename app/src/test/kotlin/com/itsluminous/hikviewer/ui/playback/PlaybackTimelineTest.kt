package com.itsluminous.hikviewer.ui.playback

import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.core.playback.RecordSegment
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class PlaybackTimelineTest {
    private val day = LocalDate.of(2026, 11, 4)
    private val morning = RecordSegment(day.atTime(6, 0), day.atTime(12, 30), RecordSegment.TYPE_TIMING)
    private val evening = RecordSegment(day.atTime(14, 0), day.plusDays(1).atStartOfDay(), RecordSegment.TYPE_TIMING)
    private val segments = listOf(morning, evening)

    @Test
    fun `fractions map the day onto the bar and back`() {
        assertThat(PlaybackTimeline.fractionOfDay(day.atStartOfDay())).isEqualTo(0f)
        assertThat(PlaybackTimeline.fractionOfDay(day.atTime(12, 0))).isEqualTo(0.5f)
        assertThat(PlaybackTimeline.fractionOfDay(day.atTime(18, 0))).isEqualTo(0.75f)
        assertThat(PlaybackTimeline.timeAt(day, 0.5f)).isEqualTo(day.atTime(12, 0))
        assertThat(PlaybackTimeline.timeAt(day, 1f)).isEqualTo(day.atTime(23, 59, 59))
        assertThat(PlaybackTimeline.timeAt(day, -3f)).isEqualTo(day.atStartOfDay())
    }

    @Test
    fun `shift keeps the playhead inside the day`() {
        assertThat(PlaybackTimeline.shift(day, day.atTime(10, 0), 10)).isEqualTo(day.atTime(10, 0, 10))
        assertThat(PlaybackTimeline.shift(day, day.atTime(0, 0, 5), -60)).isEqualTo(day.atStartOfDay())
        assertThat(PlaybackTimeline.shift(day, day.atTime(23, 59, 50), 60)).isEqualTo(day.atTime(23, 59, 59))
    }

    @Test
    fun `a held seek key accelerates after a few repeats`() {
        assertThat(PlaybackTimeline.seekStep(0)).isEqualTo(10)
        assertThat(PlaybackTimeline.seekStep(PlaybackTimeline.ACCELERATE_AFTER_REPEATS - 1)).isEqualTo(10)
        assertThat(PlaybackTimeline.seekStep(PlaybackTimeline.ACCELERATE_AFTER_REPEATS)).isEqualTo(60)
        assertThat(PlaybackTimeline.seekStep(40)).isEqualTo(60)
    }

    @Test
    fun `segment lookup snaps forward or backward across gaps`() {
        assertThat(PlaybackTimeline.segmentAtOrAfter(segments, day.atTime(7, 0))).isEqualTo(morning)
        assertThat(PlaybackTimeline.segmentAtOrAfter(segments, day.atTime(13, 0))).isEqualTo(evening)
        assertThat(PlaybackTimeline.segmentAtOrAfter(segments, day.atTime(3, 0))).isEqualTo(morning)
        assertThat(PlaybackTimeline.segmentAtOrAfter(emptyList(), day.atTime(3, 0))).isNull()
        assertThat(PlaybackTimeline.segmentAtOrBefore(segments, day.atTime(13, 0))).isEqualTo(morning)
        assertThat(PlaybackTimeline.segmentAtOrBefore(segments, day.atTime(3, 0))).isNull()
        assertThat(PlaybackTimeline.segmentAtOrBefore(segments, day.atTime(15, 0))).isEqualTo(evening)
    }

    @Test
    fun `forward windows run to the segment end, capped, and skip gaps`() {
        val inside = PlaybackTimeline.windowForward(segments, day.atTime(12, 0), Duration.ofHours(1))!!
        assertThat(inside.begin).isEqualTo(day.atTime(12, 0))
        assertThat(inside.end).isEqualTo(day.atTime(12, 30))
        val capped = PlaybackTimeline.windowForward(segments, day.atTime(6, 0), Duration.ofMinutes(10))!!
        assertThat(capped.end).isEqualTo(day.atTime(6, 10))
        val gap = PlaybackTimeline.windowForward(segments, day.atTime(13, 0), Duration.ofHours(1))!!
        assertThat(gap.begin).isEqualTo(day.atTime(14, 0))
        assertThat(PlaybackTimeline.windowForward(listOf(morning), day.atTime(13, 0), Duration.ofHours(1))).isNull()
        assertThat(PlaybackTimeline.windowForward(segments, day.atTime(12, 30), Duration.ofHours(1))!!.begin)
            .isEqualTo(day.atTime(14, 0))
    }

    @Test
    fun `hop windows are short and snap into footage in the direction of travel`() {
        val three = Duration.ofSeconds(3)
        val forward = PlaybackTimeline.windowForHop(segments, day.atTime(13, 0), +1, three)!!
        assertThat(forward.begin).isEqualTo(day.atTime(14, 0))
        assertThat(forward.end).isEqualTo(day.atTime(14, 0, 3))
        val back = PlaybackTimeline.windowForHop(segments, day.atTime(13, 0), -1, three)!!
        assertThat(back.begin).isEqualTo(day.atTime(12, 29, 57))
        assertThat(back.end).isEqualTo(day.atTime(12, 30))
        val inside = PlaybackTimeline.windowForHop(segments, day.atTime(7, 0), -1, three)!!
        assertThat(inside.begin).isEqualTo(day.atTime(7, 0))
        assertThat(PlaybackTimeline.windowForHop(segments, day.atTime(3, 0), -1, three)).isNull()
        assertThat(PlaybackTimeline.windowForHop(listOf(morning), day.atTime(13, 0), +1, three)).isNull()
        // The last second of a segment leaves nothing to play forward from.
        assertThat(PlaybackTimeline.windowForHop(listOf(morning), day.atTime(12, 29, 59), +1, three)!!.end)
            .isEqualTo(day.atTime(12, 30))
    }

    @Test
    fun `labels and speed cycle`() {
        assertThat(PlaybackTimeline.label(day.atTime(17, 13, 51))).isEqualTo("17:13:51")
        assertThat(PlaybackSpeed.NORMAL.label).isEqualTo("1x")
        assertThat(PlaybackSpeed.FORWARD_2.label).isEqualTo("+2x")
        assertThat(PlaybackSpeed.REVERSE_10.label).isEqualTo("-10x")
        assertThat(PlaybackSpeed.NORMAL.next()).isEqualTo(PlaybackSpeed.FORWARD_2)
        assertThat(PlaybackSpeed.FORWARD_10.next()).isEqualTo(PlaybackSpeed.REVERSE_10)
        assertThat(PlaybackSpeed.REVERSE_10.previous()).isEqualTo(PlaybackSpeed.FORWARD_10)
        assertThat(PlaybackSpeed.REVERSE_2.next()).isEqualTo(PlaybackSpeed.NORMAL)
        assertThat(PlaybackSpeed.entries.map { it.factor }).containsExactly(-10, -5, -2, 1, 2, 5, 10).inOrder()
        assertThat(PlaybackSpeed.entries.filter { it.isRealTime }).containsExactly(PlaybackSpeed.NORMAL)
    }
}
