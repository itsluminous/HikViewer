package com.itsluminous.hikviewer.data

import android.util.Log
import com.itsluminous.hikviewer.core.playback.RecordSegment
import com.itsluminous.hikviewer.core.playback.RecorderClock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * One [RecorderClock] per recorder (device serial), learnt from its recordings and kept across
 * launches. The clock for a recorder nothing has been learnt about runs at the device's time.
 */
interface RecorderClocks {
    /** The clock of the recorder with [serial], as currently known. */
    fun clock(serial: String): RecorderClock

    /**
     * Refines the clock of [serial] from a record search that listed [segments] for the recorder
     * day [day]; returns the clock afterwards. [todayWasEmpty] says that the caller first searched
     * the day after [day], the recorder's today as then estimated, and found nothing, so [day]'s
     * newest footage is the newest there is. See [StoredRecorderClocks.learn] for the rules.
     */
    fun learn(
        serial: String,
        day: LocalDate,
        segments: List<RecordSegment>,
        todayWasEmpty: Boolean = false,
    ): RecorderClock
}

/**
 * The production [RecorderClocks]: offsets cached per serial in [SettingsStore] and learnt from
 * the newest footage of the recorder's current day, as [RecorderClock] explains.
 *
 * What a day's listing teaches depends on what day it is and how it ends:
 *  * A day more than one day away from the device's date cannot be the recorder's today in any
 *    zone (offsets stay within a day either way) and teaches nothing: the user is browsing history.
 *  * A day whose newest footage ends *before* its midnight is still in progress on the recorder,
 *    so that end is the recorder's now, up to the cloud's index lag: [RecorderClock.estimateOffset]
 *    gives the offset exactly. Accepted when the day is the recorder's today as estimated so far,
 *    when nothing is cached yet (the first open), or when the caller found the estimated today
 *    empty ([learn]'s `todayWasEmpty`) and fell back to the day before, which heals an estimate
 *    that drifted ahead (the device's zone changed) and serves a user east of the recorder, whose
 *    device is already on a date the recorder has not reached.
 *  * A day recorded to its very end has been finished by the recorder: it is at least at that
 *    midnight, which bounds the offset from below ([RecorderClock.minimumOffset]). A larger cached
 *    offset is left alone (the user is browsing yesterday); a smaller or missing one is raised,
 *    which moves the estimated today forward so the next listing can learn the exact value.
 *
 * The one case this gets wrong: a recorder whose channel stopped recording before the recorder's
 * current day began, opened for the first time, looks like a recorder that has not reached that
 * day, until a channel that does record is opened and corrects it.
 */
class StoredRecorderClocks(
    private val settings: SettingsStore,
    private val deviceNow: () -> LocalDateTime = { LocalDateTime.now() },
) : RecorderClocks {
    override fun clock(serial: String): RecorderClock = RecorderClock(offsetOf(serial) ?: Duration.ZERO, deviceNow)

    override fun learn(
        serial: String,
        day: LocalDate,
        segments: List<RecordSegment>,
        todayWasEmpty: Boolean,
    ): RecorderClock {
        val now = deviceNow()
        val newestEnd = segments.maxOfOrNull { it.end }
        if (newestEnd == null || abs(day.toEpochDay() - now.toLocalDate().toEpochDay()) > 1) return clock(serial)
        val current = offsetOf(serial)
        val dayEnd = day.plusDays(1).atStartOfDay()
        val learnt =
            if (newestEnd.isBefore(dayEnd)) {
                val estimatedToday = current?.let { now.plus(it).toLocalDate() }
                val exact = RecorderClock.estimateOffset(newestEnd, now)
                if (current == null || day == estimatedToday || todayWasEmpty) exact else current
            } else {
                val minimum = RecorderClock.minimumOffset(dayEnd, now)
                if (current == null || minimum > current) minimum else current
            }
        if (learnt != current) settings.setRecorderOffsetMinutes(serial, learnt.toMinutes().toInt())
        Log.i(
            TAG,
            "recorder offset ${RecorderClock.formatOffset(learnt)} from segment end $newestEnd on $day" +
                " (device now $now, was ${current?.let(RecorderClock::formatOffset) ?: "unknown"}, serial $serial)",
        )
        return RecorderClock(learnt, deviceNow)
    }

    private fun offsetOf(serial: String): Duration? =
        settings.recorderOffsetMinutes(serial)?.let { Duration.ofMinutes(it.toLong()) }

    private companion object {
        const val TAG = "RecorderClocks"
    }
}
