package com.itsluminous.hikviewer.fakes

import com.itsluminous.hikviewer.core.playback.RecordSegment
import com.itsluminous.hikviewer.core.playback.RecorderClock
import com.itsluminous.hikviewer.data.RecorderClocks
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A [RecorderClocks] that never learns: every recorder runs [offset] ahead of [deviceNow]. The
 * default (no offset, the real device clock) makes the screens behave exactly as before recorder
 * time existed, which is what most tests want; [learned] records what the screens tried to teach.
 */
class FixedRecorderClocks(
    private val offset: Duration = Duration.ZERO,
    private val deviceNow: () -> LocalDateTime = { LocalDateTime.now() },
) : RecorderClocks {
    /** Every [learn] call: serial, day, newest segment end (null for an empty day), todayWasEmpty. */
    val learned = mutableListOf<Learned>()

    data class Learned(
        val serial: String,
        val day: LocalDate,
        val newestEnd: LocalDateTime?,
        val todayWasEmpty: Boolean,
    )

    override fun clock(serial: String): RecorderClock = RecorderClock(offset, deviceNow)

    override fun learn(
        serial: String,
        day: LocalDate,
        segments: List<RecordSegment>,
        todayWasEmpty: Boolean,
    ): RecorderClock {
        learned += Learned(serial, day, segments.maxOfOrNull { it.end }, todayWasEmpty)
        return clock(serial)
    }
}
