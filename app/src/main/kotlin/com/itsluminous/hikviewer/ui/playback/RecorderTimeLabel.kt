package com.itsluminous.hikviewer.ui.playback

import android.content.res.Resources
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.playback.RecorderClock
import java.time.LocalDateTime

/**
 * The time label under the bar: `HH:mm:ss` in recorder time, the same numbers the recorder burns
 * into the picture, followed by a hint such as `(recorder, +13:30)` only when the recorder's clock
 * differs from the device's, so a user in another zone knows why the two disagree. With no offset
 * the label is unchanged from before recorder time existed.
 */
object RecorderTimeLabel {
    /** The label for [time] on the axis of the recorder whose clock is [clock]. */
    fun of(
        resources: Resources,
        time: LocalDateTime,
        clock: RecorderClock,
    ): String {
        val plain = PlaybackTimeline.label(time)
        if (!clock.isShifted) return plain
        return resources.getString(R.string.time_label_recorder, plain, clock.offsetLabel())
    }
}
