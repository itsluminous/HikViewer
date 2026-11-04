package com.itsluminous.hikviewer.ui.playback

import com.itsluminous.hikviewer.core.playback.PlaybackWindow
import com.itsluminous.hikviewer.core.playback.RecordSegment
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The arithmetic behind the time bar and the seek keys, kept free of Android so it is unit-tested
 * on the JVM. All times are recorder-local like [RecordSegment].
 */
object PlaybackTimeline {
    /** Seconds in a day; the bar's scale. */
    const val DAY_SECONDS: Long = 24 * 60 * 60

    /** Seek step of a single LEFT/RIGHT press. */
    const val SEEK_STEP_SECONDS: Long = 10

    /** Seek step once a key has auto-repeated [ACCELERATE_AFTER_REPEATS] times. */
    const val FAST_SEEK_STEP_SECONDS: Long = 60

    /** Key repeats before a held LEFT/RIGHT jumps by the fast step. */
    const val ACCELERATE_AFTER_REPEATS: Int = 4

    /** Where [time] sits on a 24 h bar, 0f at midnight and 1f at the next midnight. */
    fun fractionOfDay(time: LocalDateTime): Float =
        (time.toLocalTime().toSecondOfDay().toFloat() / DAY_SECONDS).coerceIn(0f, 1f)

    /** The recorder time at [fraction] of [day]'s bar, clamped into the day. */
    fun timeAt(
        day: LocalDate,
        fraction: Float,
    ): LocalDateTime {
        val seconds = (fraction.coerceIn(0f, 1f) * DAY_SECONDS).toLong().coerceIn(0, DAY_SECONDS - 1)
        return day.atStartOfDay().plusSeconds(seconds)
    }

    /** [time] moved by [seconds] but kept inside [day]. */
    fun shift(
        day: LocalDate,
        time: LocalDateTime,
        seconds: Long,
    ): LocalDateTime = clampToDay(day, time.plusSeconds(seconds))

    /** [time] kept inside [day] (the last second of the day is the upper bound). */
    fun clampToDay(
        day: LocalDate,
        time: LocalDateTime,
    ): LocalDateTime {
        val start = day.atStartOfDay()
        val last = start.plusSeconds(DAY_SECONDS - 1)
        return when {
            time.isBefore(start) -> start
            time.isAfter(last) -> last
            else -> time
        }
    }

    /** The seek step for a key event with [repeatCount] (0 for a plain press). */
    fun seekStep(repeatCount: Int): Long =
        if (repeatCount >= ACCELERATE_AFTER_REPEATS) FAST_SEEK_STEP_SECONDS else SEEK_STEP_SECONDS

    /** The segment containing [time], else the first one starting after it; null when none is left. */
    fun segmentAtOrAfter(
        segments: List<RecordSegment>,
        time: LocalDateTime,
    ): RecordSegment? = segments.firstOrNull { it.contains(time) }
        ?: segments.filter { it.begin.isAfter(time) }.minByOrNull { it.begin }

    /** The segment containing [time], else the last one ending at or before it; null when none precedes it. */
    fun segmentAtOrBefore(
        segments: List<RecordSegment>,
        time: LocalDateTime,
    ): RecordSegment? =
        segments.firstOrNull { it.contains(time) } ?: segments.filter { !it.end.isAfter(time) }.maxByOrNull { it.end }

    /**
     * The window to open for playing forward from [time]: starts at [time] (or the next segment's
     * start when [time] is in a gap) and runs to the segment's end, at most [maxLength]. Null when
     * no footage follows [time].
     */
    fun windowForward(
        segments: List<RecordSegment>,
        time: LocalDateTime,
        maxLength: Duration,
    ): PlaybackWindow? {
        val segment = segmentAtOrAfter(segments, time) ?: return null
        val begin = maxOf(time, segment.begin)
        val end = minOf(segment.end, begin.plus(maxLength))
        if (!end.isAfter(begin)) return null
        return PlaybackWindow(begin, end)
    }

    /**
     * The short window for one hop at [time] going in [direction] (+1 forward, -1 back): snapped
     * into the nearest segment in that direction. Null when the recording runs out that way.
     */
    fun windowForHop(
        segments: List<RecordSegment>,
        time: LocalDateTime,
        direction: Int,
        length: Duration,
    ): PlaybackWindow? {
        val segment =
            (if (direction >= 0) segmentAtOrAfter(segments, time) else segmentAtOrBefore(segments, time))
                ?: return null
        val begin =
            when {
                segment.contains(time) -> time
                direction >= 0 -> segment.begin
                else -> maxOf(segment.begin, segment.end.minus(length))
            }
        val end = minOf(segment.end, begin.plus(length))
        if (!end.isAfter(begin)) return null
        return PlaybackWindow(begin, end)
    }

    /** `HH:mm:ss` for the time label. */
    fun label(time: LocalDateTime): String = TIME_LABEL.format(time)

    private val TIME_LABEL =
        java.time.format.DateTimeFormatter
            .ofPattern("HH:mm:ss")
}
