package com.itsluminous.hikviewer.ui.playback

import android.content.res.Resources
import android.view.View
import android.widget.TextView
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.playback.RecorderClock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.icu.text.SimpleDateFormat as IcuDateFormat
import android.icu.util.TimeZone as IcuTimeZone

/**
 * The two time lines under the bar. Line 1 is `HH:mm:ss` in recorder time, the same numbers the
 * recorder burns into the picture, always. Line 2 appears only when the recorder's clock differs
 * from the device's and reads `Local time  10:34 PT (+13:30)`: the device-local time that matches
 * the playhead, the device zone's short name and the learnt offset, so a user in another zone can
 * see both clocks without doing the sum. With no offset the screen is exactly the single line it
 * was before recorder time existed.
 */
object RecorderTimeLabel {
    /** `HH:mm`, the bar's 24 h style without the seconds, which the local line does not need. */
    private val localTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Fills [timeLabel] and [localLabel] for [time] on the axis of the recorder whose clock is [clock]. */
    fun bind(
        timeLabel: TextView,
        localLabel: TextView,
        time: LocalDateTime,
        clock: RecorderClock,
    ) {
        timeLabel.text = PlaybackTimeline.label(time)
        val local = localLine(localLabel.resources, time, clock)
        localLabel.text = local
        localLabel.visibility = if (local == null) View.GONE else View.VISIBLE
    }

    /**
     * Line 2 for the recorder time [time], or null when the clocks agree and there is nothing to
     * say. [zone] is the device's zone, named but never used for arithmetic: the device time comes
     * from [RecorderClock.toDevice], the offset the recorder taught us.
     */
    fun localLine(
        resources: Resources,
        time: LocalDateTime,
        clock: RecorderClock,
        zone: TimeZone = TimeZone.getDefault(),
    ): String? {
        if (!clock.isShifted) return null
        val device = clock.toDevice(time).format(localTime)
        return resources.getString(R.string.local_time_line, device, zoneName(zone), clock.offsetLabel())
    }

    /**
     * The short generic name of [zone] in the user's language, such as `PT`, `IST` or `GMT+5:30`,
     * from ICU's `v` pattern. Generic, so it does not flip between PST and PDT with the season.
     */
    fun zoneName(
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val format = IcuDateFormat("v", locale)
        format.timeZone = IcuTimeZone.getTimeZone(zone.id)
        return format.format(Date())
    }
}
