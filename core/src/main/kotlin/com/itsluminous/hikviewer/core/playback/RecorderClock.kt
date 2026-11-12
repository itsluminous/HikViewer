package com.itsluminous.hikviewer.core.playback

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Translates between the device's clock and the recorder's, without any timezone arithmetic.
 *
 * Everything the cloud says about recordings ([RecordSegment], playback windows) is the recorder's
 * own wall clock, with no zone attached, and that is also what the recorder burns into its
 * pictures. A user whose phone is in another zone must still see those numbers, so the time bar
 * stays in recorder time; what has to move is the device's idea of "now" and "today" when it is
 * laid on that axis (the live playhead, the day a playback opens on, the forward bound of the date
 * chip). [offset] is `recorder minus device`: adding it to a device time gives the recorder time.
 *
 * The offset is not configured anywhere: it is learnt from the recorder itself. The cloud does
 * expose the recorder's configured zone (`statusInfos[serial].optionals.timeZone` / `tzCode` in
 * `devices/pagelist`), but on the reference DVR it reads `UTC` / `0` while the recorder's wall
 * clock runs on IST, so that field describes a setting nobody made rather than the clock, and is
 * not used. The newest recording of the recorder's current day ends only the cloud's index lag
 * (minutes) behind the recorder's now, so `newestEnd - deviceNow` is the offset up to that lag,
 * and since every zone offset is a multiple of 15 minutes, rounding to the quarter hour removes
 * the lag ([estimateOffset]). Learning and caching live in the app's `RecorderClocks`.
 */
public class RecorderClock(
    /** Recorder time minus device time. Zero when the two clocks agree. */
    public val offset: Duration,
    private val deviceNow: () -> LocalDateTime = { LocalDateTime.now() },
) {
    /** True when the recorder's clock differs from the device's, so labels should say so. */
    public val isShifted: Boolean get() = !offset.isZero

    /** The recorder's wall clock now. */
    public fun now(): LocalDateTime = toRecorder(deviceNow())

    /** The recorder's current date. */
    public fun today(): LocalDate = now().toLocalDate()

    /** The recorder time that corresponds to [deviceTime]. */
    public fun toRecorder(deviceTime: LocalDateTime): LocalDateTime = deviceTime.plus(offset)

    /** The device time that corresponds to [recorderTime]. */
    public fun toDevice(recorderTime: LocalDateTime): LocalDateTime = recorderTime.minus(offset)

    /** The offset as `+13:30` or `-03:45`, for the time label's hint. */
    public fun offsetLabel(): String = formatOffset(offset)

    /** Estimation rules, shared with the tests. */
    public companion object {
        /** Zone offsets are multiples of this; the index lag of a few minutes is rounded away. */
        public val STEP: Duration = Duration.ofMinutes(15)

        /**
         * Added before rounding, so an index lag anywhere in `0..14` minutes lands on the true
         * offset: the newest end is always *behind* the recorder's now, never ahead of it.
         */
        public val LAG_ALLOWANCE: Duration = Duration.ofMinutes(7)

        /**
         * The offset implied by the newest footage of the recorder's current day ending at
         * [newestEnd] while the device reads [deviceNow]: their difference plus [LAG_ALLOWANCE],
         * rounded to the nearest [STEP].
         */
        public fun estimateOffset(
            newestEnd: LocalDateTime,
            deviceNow: LocalDateTime,
        ): Duration = roundToStep(Duration.between(deviceNow, newestEnd).plus(LAG_ALLOWANCE))

        /**
         * The smallest offset consistent with a day whose footage runs to its very end ([dayEnd],
         * the next midnight): the recorder has at least reached that midnight. Rounded up to the
         * next [STEP], so the recorder's estimated now does not fall back into the finished day.
         */
        public fun minimumOffset(
            dayEnd: LocalDateTime,
            deviceNow: LocalDateTime,
        ): Duration = ceilToStep(Duration.between(deviceNow, dayEnd))

        /** [duration] rounded to the nearest [STEP] (halves round up). */
        public fun roundToStep(duration: Duration): Duration {
            val step = STEP.seconds
            return Duration.ofSeconds(Math.floorDiv(duration.seconds + step / 2, step) * step)
        }

        /** [duration] rounded up to the next [STEP]. */
        public fun ceilToStep(duration: Duration): Duration {
            val step = STEP.seconds
            return Duration.ofSeconds(-Math.floorDiv(-duration.seconds, step) * step)
        }

        /** `+HH:MM` / `-HH:MM`. */
        public fun formatOffset(offset: Duration): String {
            val minutes = abs(offset.toMinutes())
            val sign = if (offset.isNegative) "-" else "+"
            return "%s%02d:%02d".format(sign, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
        }

        private const val MINUTES_PER_HOUR = 60
    }
}
