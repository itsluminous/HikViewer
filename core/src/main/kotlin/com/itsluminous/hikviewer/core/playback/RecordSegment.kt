package com.itsluminous.hikviewer.core.playback

import java.time.Duration
import java.time.LocalDateTime

/**
 * One span of recorded footage on the recorder's disk.
 *
 * Times are the **recorder's wall clock** with no zone (the cloud reports `2026-11-03T10:19:41`
 * and the relay wants the same clock back). The UI labels them as recorder time rather than
 * converting, because a converted seek would land at the wrong moment when the viewer's zone
 * differs from the recorder's.
 */
public data class RecordSegment(
    /** First recorded second. */
    val begin: LocalDateTime,
    /** Last recorded second (exclusive end for our purposes). */
    val end: LocalDateTime,
    /** Recorder's record type: `TIMING` (continuous) or `EVENT` (motion); anything else is kept as is. */
    val type: String,
) {
    /** True when [time] falls inside the segment. */
    public fun contains(time: LocalDateTime): Boolean = !time.isBefore(begin) && time.isBefore(end)

    /** Length of the segment. */
    public val duration: Duration get() = Duration.between(begin, end)

    /** Record types the recorder uses. */
    public companion object {
        /** Continuous recording. */
        public const val TYPE_TIMING: String = "TIMING"

        /** Motion/alarm recording. */
        public const val TYPE_EVENT: String = "EVENT"
    }
}

/** The span a playback stream is asked for; recorder-local like [RecordSegment]. */
public data class PlaybackWindow(
    /** First second to play. */
    val begin: LocalDateTime,
    /** Second to stop at. The relay rounds up to the next GOP, then closes the stream. */
    val end: LocalDateTime,
) {
    init {
        require(end.isAfter(begin)) { "playback window must end after it begins" }
    }

    /** Window length. */
    public val duration: Duration get() = Duration.between(begin, end)
}
